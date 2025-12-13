package com.sky.service.impl;

import com.sky.dto.DishDTO;
import com.sky.mapper.DishMapper;
import com.sky.service.DishService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DishServiceImpl implements DishService {

    @Autowired
    private DishMapper dishMapper;
    @Override
    public void save(DishDTO dishDTO) {
        dishMapper.insert(dishDTO);
    }
}
