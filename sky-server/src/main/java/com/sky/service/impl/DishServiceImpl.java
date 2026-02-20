package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.result.PageResult;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class DishServiceImpl implements DishService {

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 新增菜品和对应的口味
     * @param dishDTO
     */
    @Override
    public void saveWithFlavor(DishDTO dishDTO) {
        Dish dish=new Dish();
        BeanUtils.copyProperties(dishDTO,dish);
        dishMapper.insert(dish);

        long dishId=dish.getId();//获取当前菜品的id

        List<DishFlavor> dishFlavors=dishDTO.getFlavors();//获取当前菜品的口味
        if(dishFlavors!=null&&dishFlavors.size()>0){
            dishFlavors.forEach(dishFlavor ->dishFlavor.setDishId(dishId));//给每个口味设置当前菜品的id
//          等价于dishFlavors.forEach(new Consumer<DishFlavor>() {
//                @Override
//                public void accept(DishFlavor dishFlavor) {
//                    dishFlavor.setDishId(dishId);
//                }
//            });
        }
        dishFlavorMapper.insertBatch(dishFlavors);

        //清理缓存
        redisTemplate.delete("dish_"+dishDTO.getCategoryId() );
    }

    /**
     * 菜品分页查询
     * @param dishPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        Page<Dish> page = dishMapper.pageQuery(dishPageQueryDTO);
        long total = page.getTotal();
        List<Dish> records = page.getResult();
        return new PageResult(total, records);
    }

    /**
     * 批量删除
     * @param ids
     */
    @Override
    public void deleteBatch(List<Long> ids) {
        //要删除的分类 id的集合
        Set<String> keys = new HashSet<>();

        for(Long id:ids) {
            Dish dish = dishMapper.getById(id);
            keys.add("dish_"+dish.getCategoryId());
            //菜品是否启用
            if(dish.getStatus()== StatusConstant.ENABLE){
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
            }
        }
        //菜品是否关联套餐
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if(setmealIds!=null&&setmealIds.size()>0){
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }
        //删除关联口味
        dishFlavorMapper.deleteByDishIds(ids);
        //删除菜品
        ids.forEach(id -> dishMapper.deleteById(id));

        //清理缓存
        redisTemplate.delete(keys);
    }

    /**
     * 菜品修改
     * @param dishDTO
     */
    @Override
    public void update(DishDTO dishDTO) {
        //先获取修改之前的分类id,以用于删除缓存
        Dish predish = dishMapper.getById(dishDTO.getId());
        long preCategoryId = predish.getCategoryId();

        //菜品对象
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO,dish);
        dishMapper.update(dish);
        //删除所有菜品口味
        dishFlavorMapper.deleteByDishIds(Arrays.asList(dish.getId()));
        //添加新的菜品口味
        List<DishFlavor> dishFlavors = dishDTO.getFlavors();
        if(dishFlavors!=null&&dishFlavors.size()>0){
            dishFlavors.forEach(dishFlavor -> dishFlavor.setDishId(dish.getId()));
            dishFlavorMapper.insertBatch(dishFlavors);
        }

        //清理修改之前的缓存
        redisTemplate.delete("dish_"+preCategoryId );
        //清理修改之后的缓存
        if(preCategoryId!=dishDTO.getCategoryId()) {
            redisTemplate.delete("dish_" + dishDTO.getCategoryId());
        }
    }

    /**
     * 菜品起售停售
     * @param status
     * @param id
     */
    @Override
    public void startOrStop(Integer status, Long id) {
        Dish dish = Dish.builder()
                .id(id)
                .status(status)
                .build();
        dishMapper.update(dish);
        // 先查询数据库获取 categoryId
        Dish dishFromDb = dishMapper.getById(id);

        //清理缓存
        if (dishFromDb != null) {
            redisTemplate.delete("dish_" + dishFromDb.getCategoryId());
        }
    }

    /**
     * 根据id查询菜品和对应的口味
     * @param id
     * @return
     */
    @Override
    public DishDTO getByIdWithFlavor(Long id) {
        Dish dish = dishMapper.getById(id);
        DishDTO dishDTO = new DishDTO();
        BeanUtils.copyProperties(dish,dishDTO);
        dishDTO.setFlavors(dishFlavorMapper.getByDishId(id));
        return dishDTO;
    }

    /**
     * 根据分类id查询菜品（用户端 - C端）
     * 1. 需要缓存
     * 2. 需要返回口味数据 (DishVO)
     * @param categoryId
     * @return
     */
    public List<DishVO> listWithFlavor(Long categoryId) {
        // 1. 构造 Redis Key
        String key = "dish_" + categoryId;

        // 2. 查询缓存
        ValueOperations valueOperations = redisTemplate.opsForValue();
        // 这里强转 List<DishVO>，因为缓存里存的是带口味的数据
        List<DishVO> dishVOList = (List<DishVO>) valueOperations.get(key);

        // 3. 缓存命中，直接返回
        if (dishVOList != null && !dishVOList.isEmpty()) {
            return dishVOList;
        }

        // 4. 缓存未命中，查询数据库
        Dish dish = Dish.builder()
                .categoryId(categoryId)
                .status(StatusConstant.ENABLE) // 用户端只能查起售的
                .build();

        // 4.1 先查菜品基本信息
        List<Dish> dishList = dishMapper.list(dish);

        dishVOList = new ArrayList<>();

        // 4.2 组装口味数据 (这一步是为了把 Dish 转为 DishVO)
        for (Dish d : dishList) {
            DishVO dishVO = new DishVO();
            BeanUtils.copyProperties(d, dishVO);

            // 根据菜品id查询对应的口味
            List<DishFlavor> flavors = dishFlavorMapper.getByDishId(d.getId());
            dishVO.setFlavors(flavors);

            dishVOList.add(dishVO);
        }

        // 5. 将结果写入缓存
        // 防止缓存穿透：如果查询结果为空，也缓存一个空集合，但过期时间短一点
        if (dishVOList.isEmpty()) {
            // 空数据缓存 5 分钟 (防止短时间内大量请求打到数据库)
            valueOperations.set(key, new ArrayList<>(), 5, TimeUnit.MINUTES);
        } else {
            // 正常数据缓存 60 分钟 + 随机时间 (防止缓存雪崩)
            valueOperations.set(key, dishVOList, 60 + (new Random().nextInt(10)), TimeUnit.MINUTES);
        }

        return dishVOList;
    }

    /**
     * 根据分类id查询菜品（管理端 - B端）
     * 1. 不需要缓存 (保证数据实时性)
     * 2. 只需要基本信息 (Dish) 用于新增套餐时的列表展示
     * @param categoryId
     * @return
     */
    @Override
    public List<Dish> list(Long categoryId) {
        Dish dish = Dish.builder()
                .categoryId(categoryId)
                .status(StatusConstant.ENABLE) // 管理端新增套餐时，也只能选起售的菜
                .build();
        return dishMapper.list(dish);
    }
}

