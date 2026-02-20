package com.sky.mapper;

import com.sky.entity.OrderDetail;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface OrderDetailMapper {

    /**
     * 根据订单id查询订单详情
     * @param orderId 订单id
     * @return 订单详情列表
     */
    List<OrderDetail> listByOrderId(Long orderId);

    /**
     * 批量插入订单详情
     * @param orderDetails 订单详情列表
     */
    void insertBatch(List<OrderDetail> orderDetails);

    /**
     * 根据订单id删除订单详情
     * @param orderId 订单id
     */
    void deleteByOrderId(Long orderId);
}
