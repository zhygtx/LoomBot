package com.loom.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loom.system.domain.Menu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MenuMapper extends BaseMapper<Menu> {

    /**
     * 某个父级下还有几个子菜单。
     *
     * <p>删除菜单前的必查项：直接删父级会留下一批 {@code parent_id} 指向不存在记录的孤儿菜单， 它们不会出现在任何一棵树里，只会在列表接口里以「顶级菜单」的身份冒出来。
     */
    @Select("SELECT COUNT(1) FROM sys_menu WHERE parent_id = #{parentId}")
    long countChildren(Long parentId);
}
