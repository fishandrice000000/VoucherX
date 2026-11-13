package com.voucherx.maintenance;

import com.voucherx.VoucherXApplication;
import com.voucherx.reconcile.CompensationService;
import com.voucherx.reconcile.ReconciliationService;
import org.springframework.boot.SpringApplication;

/**
 * 本机维护入口，给拥有部署配置和凭据的操作环境使用，没有普通用户 HTTP 管理路由。
 * <p>
 * 05 提供 diagnose、repair、close、compensate；06 提供 freeze、recover、status。
 * 这不是额外的命令鉴权层，具体权限依赖运行环境及数据库/Redis 凭据。
 * <p>
 * 启动同一个应用上下文但关闭 Web、自动投递、对账及消费者启动，并启用 maintenance-mode 抑制相关后台初始化/任务。
 * 业务诊断与状态查询只读；修复、关闭、释放和恢复会更改业务数据，不能把所有命令都当成查询。
 *
 * @see com.voucherx.reconcile.ReconciliationService
 * @see com.voucherx.reconcile.CompensationService
 */
public final class MaintenanceCli {
    private MaintenanceCli() {
    }

    /**
     * 要求恰好两个参数，再以显式维护配置启动 Spring 上下文，选择相应服务调用并打印结果，结束时关闭上下文。
     * <p>
     * diagnose 只收集请求事实；repair 可能同步、受理、重投或释放；close 仅 prepare 后打印准备标签，
     * compensate 才调用 execute。两个命令之间不保持同一个事务，准备返回标签不等于已经返还。
     * <p>
     * freeze/recover/status 的第二参数按活动数字 ID 解析，其余按请求 ID 使用。
     * 本入口不自行构造发布消息或无条件改库存，业务前提仍由各服务校验。
     *
     * @param args
     *                 命令名及请求 ID，或 freeze/recover/status 与活动 ID
     * @throws IllegalArgumentException
     *                                      参数数量、命令或活动 ID 不合法
     * @see com.voucherx.reconcile.CompensationService#prepare
     * @see com.voucherx.reconcile.CompensationService#execute
     */
    public static void main(String[] args) {
        if (args.length != 2)
            throw new IllegalArgumentException(
                    "用法: MaintenanceCli diagnose|repair|close|compensate requestId 或 freeze|recover|status voucherId");
        SpringApplication app = new SpringApplication(VoucherXApplication.class);
        try (var context = app.run("--spring.main.web-application-type=none", "--voucherx.delivery.enabled=false",
                "--voucherx.reconcile.enabled=false", "--voucherx.maintenance-mode=true",
                "--spring.rabbitmq.listener.simple.auto-startup=false")) {
            Object result = switch (args[0]) {
                case "freeze" -> context.getBean(com.voucherx.recovery.RecoveryService.class)
                        .freeze(Long.parseLong(args[1]), "OPERATOR_FREEZE");
                case "recover" ->
                    context.getBean(com.voucherx.recovery.RecoveryService.class).recover(Long.parseLong(args[1]));
                case "status" ->
                    context.getBean(com.voucherx.recovery.RecoveryService.class).status(Long.parseLong(args[1]));
                case "diagnose" -> context.getBean(ReconciliationService.class).diagnose(args[1]);
                case "repair" -> context.getBean(ReconciliationService.class).repair(args[1]);
                case "close" -> {
                    context.getBean(CompensationService.class).prepare(args[1], "OPERATOR_CLOSED");
                    yield "COMPENSATION_PENDING";
                }
                case "compensate" -> context.getBean(CompensationService.class).execute(args[1]);
                default -> throw new IllegalArgumentException("未知维护命令");
            };
            System.out.println(result);
        }
    }
}
