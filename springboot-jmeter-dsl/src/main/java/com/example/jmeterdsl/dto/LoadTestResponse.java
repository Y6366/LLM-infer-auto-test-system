package com.example.jmeterdsl.dto;

public class LoadTestResponse {

    private final long totalSamples;
    private final long errorSamples;
    private final double errorPercentage;
    private final double throughputPerSecond;
    private final double averageResponseTimeMs;
    private final double percentile95ResponseTimeMs;

    public LoadTestResponse(long totalSamples, long errorSamples, double errorPercentage,
                            double throughputPerSecond, double averageResponseTimeMs,
                            double percentile95ResponseTimeMs) {
        this.totalSamples = totalSamples;
        this.errorSamples = errorSamples;
        this.errorPercentage = errorPercentage;
        this.throughputPerSecond = throughputPerSecond;
        this.averageResponseTimeMs = averageResponseTimeMs;
        this.percentile95ResponseTimeMs = percentile95ResponseTimeMs;
    }

    public long getTotalSamples() {
        return totalSamples;
    }

    public long getErrorSamples() {
        return errorSamples;
    }

    public double getErrorPercentage() {
        return errorPercentage;
    }

    public double getThroughputPerSecond() {
        return throughputPerSecond;
    }

    public double getAverageResponseTimeMs() {
        return averageResponseTimeMs;
    }

    public double getPercentile95ResponseTimeMs() {
        return percentile95ResponseTimeMs;
    }
}
