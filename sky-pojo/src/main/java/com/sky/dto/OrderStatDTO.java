package com.sky.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class OrderStatDTO {
    private LocalDate date;
    private Integer totalCount; // 订单总数
    private Integer validCount; // 有效订单数
}