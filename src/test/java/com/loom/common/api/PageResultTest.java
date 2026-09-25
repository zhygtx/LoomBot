package com.loom.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("分页结构 PageResult")
class PageResultTest {

    @Test
    @DisplayName("应正确计算总页数")
    void shouldComputePages() {
        PageResult<String> result = PageResult.of(List.of("a", "b"), 100, 1, 10);

        assertThat(result.records()).hasSize(2);
        assertThat(result.total()).isEqualTo(100);
        assertThat(result.pageNum()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.pages()).isEqualTo(10);
    }

    @Test
    @DisplayName("除不尽时应向上取整 —— 101 条 / 每页 10 条 = 11 页")
    void shouldRoundUpPages() {
        assertThat(PageResult.of(List.of(), 101, 1, 10).pages()).isEqualTo(11);
    }

    @Test
    @DisplayName("pageSize 为 0 时不应除零崩溃")
    void shouldTolerateZeroPageSize() {
        assertThat(PageResult.of(List.of(), 10, 1, 0).pages()).isZero();
    }

    @Test
    @DisplayName("空分页应保留页码与每页条数")
    void emptyShouldKeepPagingParams() {
        PageResult<String> result = PageResult.empty(2, 20);

        assertThat(result.records()).isEmpty();
        assertThat(result.total()).isZero();
        assertThat(result.pages()).isZero();
        assertThat(result.pageNum()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(20);
    }
}
