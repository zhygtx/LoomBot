package com.loombot.workflow.event;

/**
 * 定时触发集合发生变化，需要立刻把快照推给适配器。
 *
 * <p>不直接调 {@code WorkflowScheduleSyncService} 是为了不形成环：那个服务依赖 {@code WorkflowService}， 而 {@code
 * WorkflowService} 又依赖提醒服务。发事件让两边都只依赖 Spring 的事件机制。
 */
public record WorkflowSchedulesChangedEvent() {}
