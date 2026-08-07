package top.aole.vend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智慧园区售卖机 ERP 后端入口。
 * {@code @EnableScheduling}:开启定时任务(PDCA 到期回查每日跑,M4-8 P1-2 兑现"到期自动回查")。
 */
@EnableScheduling
@SpringBootApplication
public class VendApplication {

    public static void main(String[] args) {
        SpringApplication.run(VendApplication.class, args);
    }
}
