package com.sky.annotation;

import com.sky.enumeration.OperationType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自定义注解，用于标识需要自动填充的字段
 *设置一个枚举值，表示填充数据的操作类型
 */
@Target(ElementType.METHOD)// 表示该注解用于方法
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoFill {
    OperationType value();// 设置一个枚举值，表示填充数据的操作类型
}
