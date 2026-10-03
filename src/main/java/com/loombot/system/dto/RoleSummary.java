package com.loombot.system.dto;

/**
 * 角色列表里的一行。
 *
 * <p>原来带一个 {@code enabled}（来自 {@code sys_role.status}）。那一列已删除：角色是一组权限的集合，
 * 不想让人用就把它从用户身上摘掉；留一个「停用但还绑着用户」的中间态，只会让「这个角色的权限 到底算不算数」变成一个每次都要重新确认的问题。
 */
public record RoleSummary(Long id, String code, String name) {}
