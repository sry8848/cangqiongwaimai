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
import com.sky.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
@Slf4j
public class DishServiceImpl implements DishService {

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;

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
        for(Long id:ids) {
            Dish dish = dishMapper.getById(id);
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
    }

    /**
     * 菜品修改
     * @param dishDTO
     */
    @Override
    public void update(DishDTO dishDTO) {
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
    }

    @Override
    public DishDTO getByIdWithFlavor(Long id) {
        Dish dish = dishMapper.getById(id);
        DishDTO dishDTO = new DishDTO();
        BeanUtils.copyProperties(dish,dishDTO);
        dishDTO.setFlavors(dishFlavorMapper.getByDishId(id));
        return dishDTO;
    }


}
