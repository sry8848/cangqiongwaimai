package com.sky.mapper;

import com.sky.dto.UserStatDTO;
import com.sky.entity.User;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper {
    /**
     * 根据openid查询用户
     * @param openid
     * @return
     */
    User getByOpenid(String openid);

    /**
     * 插入用户数据
     * @param user
     */
    void insert(User user);

    /**
     * 根据id查询用户
     * @param id
     * @return
     */
    User getById(Long id);

    /**
     * 根据时间范围统计每天用户数量
     * @param  start
     * @param  end
     * @return
     */
    List<UserStatDTO> getDateCountByDateRange(LocalDateTime start, LocalDateTime end);

    /**
     * 查询某日前用户总数
     * @param begin
     * @return
     */
    Long countBeforeDate(LocalDateTime begin);

    /**
     * 根据条件统计用户数量
     * @param map
     * @return
     */
    Integer countByMap(Map map);
}
