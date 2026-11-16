package com.voucherx.reconcile.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 修复审计表的 MyBatis 插入入口，SQL 位于同名 XML；是否与业务动作原子提交取决于调用方事务。
 */
@Mapper
public interface RepairAuditMapper {
    /**
     * 插入一条动作、依据、结果和时间，供维护入口追踪修复过程。
     *
     * @param request
     *                     关联请求 ID，可为空
     * @param voucher
     *                     活动 ID
     * @param action
     *                     修复动作名称
     * @param evidence
     *                     已由上层限长的非空依据文本
     * @param outcome
     *                     动作标签或结果
     * @param now
     *                     审计创建 Unix 毫秒
     * @return 插入行数
     */
    int insert(@Param("request") String request, @Param("voucher") long voucher, @Param("action") String action,
            @Param("evidence") String evidence, @Param("outcome") String outcome, @Param("now") long now);
}
