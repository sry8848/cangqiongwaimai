package com.sky.aspect;

import com.sky.annotation.AutoFill;
import com.sky.context.BaseContext;
import com.sky.enumeration.OperationType;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Created with IntelliJ IDEA.
 *
 * @Package: com.sky.aspect
 * @ClassName: AutoFillAspect.java
 * @author: shkstart
 * @createTime: 2023-03-15 16:05
 * @version: 1.0
 * 捕获所有带有@AutoFill注解的方法，获取当前时间并设置到参数中
 */
@Component
@Aspect
@Slf4j
public class AutoFillAspect {
    //获取所有在mapper包下，带有@AutoFill注解的方法
    @Pointcut("execution(* com.sky.mapper.*.*(..)) && @annotation(com.sky.annotation.AutoFill)")
    public void autoFillPointCut(){};

    @Before("autoFillPointCut()")
    public void autoFill(JoinPoint joinPoint){
        log.info("开始进行数据填充");
        //从注解中获取操作类型
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        AutoFill autoFill = signature.getMethod().getAnnotation(AutoFill.class);
        OperationType value = autoFill.value();
        //从切入点获取当前方法参数
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0){
            return;
        }
        Object object = args[0];
        //准备赋值数据
        LocalDateTime now = LocalDateTime.now();
        long currentId = BaseContext.getCurrentId();
        //利用反射赋值
        if(OperationType.INSERT== value){
            try {
                object.getClass().getDeclaredMethod("setUpdateTime",LocalDateTime.class).invoke(object,now);
                object.getClass().getDeclaredMethod("setUpdateUser",Long.class).invoke(object,currentId);
                object.getClass().getDeclaredMethod("setCreateTime",LocalDateTime.class).invoke(object,now);
                object.getClass().getDeclaredMethod("setCreateUser",Long.class).invoke(object,currentId);
            } catch (Exception e) {
                e.printStackTrace();
            }
            log.info("为{}赋值完成",object);
        }
        if(OperationType.UPDATE== value){
            try {
                object.getClass().getDeclaredMethod("setUpdateTime",LocalDateTime.class).invoke(object,now);
                object.getClass().getDeclaredMethod("setUpdateUser",Long.class).invoke(object,currentId);
            } catch (Exception e) {
                e.printStackTrace();
            }
            log.info("为{}赋值完成",object);
        }
    }
}
