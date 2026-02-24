package com.sky.dto;

import lombok.Data;
import java.time.LocalDate;

@Data
public class TurnoverStatDTO {
    // 对应 SQL: DATE(order_time) as date
    private LocalDate date;
    // 对应 SQL: SUM(amount) as amount
    private Double amount;
}