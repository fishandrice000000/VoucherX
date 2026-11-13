package com.voucherx.maintenance;

import com.voucherx.VoucherXApplication;
import com.voucherx.reconcile.CompensationService;
import com.voucherx.reconcile.ReconciliationService;
import org.springframework.boot.SpringApplication;

/** 仅限具备部署凭据的本地操作，无普通用户 HTTP 管理入口。 */
public final class MaintenanceCli {
    private MaintenanceCli() {
    }

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
