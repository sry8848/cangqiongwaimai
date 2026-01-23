package com.sky.mapper;

import com.sky.annotation.AutoFill;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.enumeration.OperationType;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DishFlavorMapper {

    /**
     * 新增菜品口味
     *
     * @param dishFlavors
     */
    void insertBatch(List<DishFlavor> dishFlavors);

    /**
     * 根据菜品id批量删除
     * @param ids
     */
    void deleteByDishIds(List<Long> ids);

    /**
     * 根据菜品id查询菜品口味数据
     * @param id
     * @return
     */
    List<DishFlavor> getByDishId(Long id);
}
