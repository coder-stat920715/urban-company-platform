package com.urbancompany.platform.config;

import com.urbancompany.platform.common.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {
    private NewTopic t(String n) { return TopicBuilder.name(n).partitions(3).replicas(1).build(); }
    @Bean NewTopic bookingTopic() { return t(Topics.BOOKING); }
    @Bean NewTopic dispatchTopic() { return t(Topics.DISPATCH); }
    @Bean NewTopic paymentTopic() { return t(Topics.PAYMENT); }
    @Bean NewTopic earningsTopic() { return t(Topics.EARNINGS); }
    @Bean NewTopic partnerTopic() { return t(Topics.PARTNER); }
}
