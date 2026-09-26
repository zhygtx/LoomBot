package com.loom.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loom.system.domain.Menu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MenuMapper extends BaseMapper<Menu> {

    @Select("SELECT COUNT(1) FROM sys_menu WHERE parent_id = #{parentId} AND deleted = 0")
    long countChildren(Long parentId);
}
