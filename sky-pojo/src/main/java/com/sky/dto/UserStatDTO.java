package com.sky.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCountDTO implements Serializable {

    // 对应 SQL 里的 DATE(create_time) AS date
    private LocalDate date;

    // 对应 SQL 里的 COUNT(id) AS count
    private Long count;

}