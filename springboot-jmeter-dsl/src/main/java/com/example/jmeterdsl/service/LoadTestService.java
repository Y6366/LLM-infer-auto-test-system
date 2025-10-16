package com.example.jmeterdsl.service;

import com.example.jmeterdsl.dto.LoadTestRequest;
import com.example.jmeterdsl.dto.LoadTestResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;
import us.abstracta.jmeter.javadsl.http.DslHttpSampler;
import us.abstracta.jmeter.javadsl.http.HttpMethod;

import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;

@Service
public class LoadTestService {

    public LoadTestResponse runTest(LoadTestRequest request) {
        DslHttpSampler sampler = buildSampler(request);
        TestPlanStats stats = testPlan(
                threadGroup("dynamic-load-test", request.getThreads(), request.getLoopCount(), sampler)
        ).run();

        long totalSamples = stats.overall().samplesCount();
        long errorSamples = stats.overall().errorsCount();
        double errorPercentage = totalSamples == 0 ? 0.0 : (errorSamples * 100.0) / totalSamples;
        double throughput = stats.overall().throughput();
        double averageResponseTime = stats.overall().sampleTimeMean();
        double percentile95 = stats.overall().sampleTimePercentile95();

        return new LoadTestResponse(totalSamples, errorSamples, errorPercentage,
                throughput, averageResponseTime, percentile95);
    }

    private DslHttpSampler buildSampler(LoadTestRequest request) {
        String normalizedPath = request.getPath().startsWith("/") ? request.getPath() : "/" + request.getPath();
        String url = String.format("%s://%s:%d%s", request.getProtocol(), request.getIp(), request.getPort(), normalizedPath);
        HttpMethod httpMethod = HttpMethod.valueOf(request.getMethod().toUpperCase());

        DslHttpSampler sampler = httpSampler("http-request", url)
                .method(httpMethod);

        if (!HttpMethod.GET.equals(httpMethod) && StringUtils.hasText(request.getBody())) {
            sampler = sampler.body(request.getBody());
        }

        if (StringUtils.hasText(request.getContentType())) {
            sampler = sampler.header("Content-Type", request.getContentType());
        }

        for (var entry : request.getHeaders().entrySet()) {
            sampler = sampler.header(entry.getKey(), entry.getValue());
        }

        return sampler;
    }
}
