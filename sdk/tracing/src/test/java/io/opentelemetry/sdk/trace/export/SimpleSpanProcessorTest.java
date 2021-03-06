/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.trace.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.ConfigBuilderTest.ConfigTester;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.Samplers;
import io.opentelemetry.sdk.trace.TestUtils;
import io.opentelemetry.sdk.trace.TracerSdkProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessorTest.WaitingSpanExporter;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.SpanContext;
import io.opentelemetry.trace.SpanId;
import io.opentelemetry.trace.TraceFlags;
import io.opentelemetry.trace.TraceId;
import io.opentelemetry.trace.TraceState;
import io.opentelemetry.trace.Tracer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link SimpleSpanProcessor}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SimpleSpanProcessorTest {
  private static final long MAX_SCHEDULE_DELAY_MILLIS = 500;
  private static final String SPAN_NAME = "MySpanName";
  @Mock private ReadableSpan readableSpan;
  @Mock private ReadWriteSpan readWriteSpan;
  @Mock private SpanExporter spanExporter;
  private final TracerSdkProvider tracerSdkFactory = TracerSdkProvider.builder().build();
  private final Tracer tracer = tracerSdkFactory.get("SimpleSpanProcessor");
  private static final SpanContext SAMPLED_SPAN_CONTEXT =
      SpanContext.create(
          TraceId.getInvalid(),
          SpanId.getInvalid(),
          TraceFlags.getSampled(),
          TraceState.builder().build());
  private static final SpanContext NOT_SAMPLED_SPAN_CONTEXT = SpanContext.getInvalid();

  private SimpleSpanProcessor simpleSampledSpansProcessor;

  @BeforeEach
  void setUp() {
    simpleSampledSpansProcessor = SimpleSpanProcessor.builder(spanExporter).build();
  }

  @Test
  void configTest() {
    Map<String, String> options = new HashMap<>();
    options.put("otel.ssp.export.sampled", "false");
    SimpleSpanProcessor.Builder config =
        SimpleSpanProcessor.builder(spanExporter)
            .fromConfigMap(options, ConfigTester.getNamingDot());
    assertThat(config.getExportOnlySampled()).isEqualTo(false);
  }

  @Test
  void configTest_EmptyOptions() {
    SimpleSpanProcessor.Builder config =
        SimpleSpanProcessor.builder(spanExporter)
            .fromConfigMap(Collections.emptyMap(), ConfigTester.getNamingDot());
    assertThat(config.getExportOnlySampled())
        .isEqualTo(SimpleSpanProcessor.Builder.DEFAULT_EXPORT_ONLY_SAMPLED);
  }

  @Test
  void onStartSync() {
    simpleSampledSpansProcessor.onStart(Context.root(), readWriteSpan);
    verifyNoInteractions(spanExporter);
  }

  @Test
  void onEndSync_SampledSpan() {
    SpanData spanData = TestUtils.makeBasicSpan();
    when(readableSpan.getSpanContext()).thenReturn(SAMPLED_SPAN_CONTEXT);
    when(readableSpan.toSpanData()).thenReturn(spanData);
    simpleSampledSpansProcessor.onEnd(readableSpan);
    verify(spanExporter).export(Collections.singletonList(spanData));
  }

  @Test
  void onEndSync_NotSampledSpan() {
    when(readableSpan.getSpanContext()).thenReturn(NOT_SAMPLED_SPAN_CONTEXT);
    simpleSampledSpansProcessor.onEnd(readableSpan);
    verifyNoInteractions(spanExporter);
  }

  @Test
  void onEndSync_OnlySampled_NotSampledSpan() {
    when(readableSpan.getSpanContext()).thenReturn(NOT_SAMPLED_SPAN_CONTEXT);
    when(readableSpan.toSpanData())
        .thenReturn(TestUtils.makeBasicSpan())
        .thenThrow(new RuntimeException());
    SimpleSpanProcessor simpleSpanProcessor = SimpleSpanProcessor.builder(spanExporter).build();
    simpleSpanProcessor.onEnd(readableSpan);
    verifyNoInteractions(spanExporter);
  }

  @Test
  void onEndSync_OnlySampled_SampledSpan() {
    when(readableSpan.getSpanContext()).thenReturn(SAMPLED_SPAN_CONTEXT);
    when(readableSpan.toSpanData())
        .thenReturn(TestUtils.makeBasicSpan())
        .thenThrow(new RuntimeException());
    SimpleSpanProcessor simpleSpanProcessor = SimpleSpanProcessor.builder(spanExporter).build();
    simpleSpanProcessor.onEnd(readableSpan);
    verify(spanExporter).export(Collections.singletonList(TestUtils.makeBasicSpan()));
  }

  @Test
  void tracerSdk_NotSampled_Span() {
    WaitingSpanExporter waitingSpanExporter =
        new WaitingSpanExporter(1, CompletableResultCode.ofSuccess());

    tracerSdkFactory.addSpanProcessor(
        BatchSpanProcessor.builder(waitingSpanExporter)
            .setScheduleDelayMillis(MAX_SCHEDULE_DELAY_MILLIS)
            .build());

    TestUtils.startSpanWithSampler(tracerSdkFactory, tracer, SPAN_NAME, Samplers.alwaysOff())
        .startSpan()
        .end();
    TestUtils.startSpanWithSampler(tracerSdkFactory, tracer, SPAN_NAME, Samplers.alwaysOff())
        .startSpan()
        .end();

    Span span = tracer.spanBuilder(SPAN_NAME).startSpan();
    span.end();

    // Spans are recorded and exported in the same order as they are ended, we test that a non
    // sampled span is not exported by creating and ending a sampled span after a non sampled span
    // and checking that the first exported span is the sampled span (the non sampled did not get
    // exported).
    List<SpanData> exported = waitingSpanExporter.waitForExport();
    // Need to check this because otherwise the variable span1 is unused, other option is to not
    // have a span1 variable.
    assertThat(exported).containsExactly(((ReadableSpan) span).toSpanData());
  }

  @Test
  void tracerSdk_NotSampled_RecordingEventsSpan() {
    // TODO(bdrutu): Fix this when Sampler return RECORD_ONLY option.
    /*
    tracer.addSpanProcessor(
        BatchSpanProcessor.builder(waitingSpanExporter)
            .setScheduleDelayMillis(MAX_SCHEDULE_DELAY_MILLIS)
            .reportOnlySampled(false)
            .build());

    io.opentelemetry.trace.Span span =
        tracer
            .spanBuilder("FOO")
            .setSampler(Samplers.neverSample())
            .startSpanWithSampler();
    span.end();

    List<SpanData> exported = waitingSpanExporter.waitForExport(1);
    assertThat(exported).containsExactly(((ReadableSpan) span).toSpanData());
    */
  }

  @Test
  void onEndSync_ExporterReturnError() {
    SpanData spanData = TestUtils.makeBasicSpan();
    when(readableSpan.getSpanContext()).thenReturn(SAMPLED_SPAN_CONTEXT);
    when(readableSpan.toSpanData()).thenReturn(spanData);
    simpleSampledSpansProcessor.onEnd(readableSpan);
    // Try again, now will no longer return error.
    simpleSampledSpansProcessor.onEnd(readableSpan);
    verify(spanExporter, times(2)).export(Collections.singletonList(spanData));
  }

  @Test
  void shutdown() {
    simpleSampledSpansProcessor.shutdown();
    verify(spanExporter).shutdown();
  }

  @Test
  void buildFromProperties_defaultSampledFlag() {
    Properties properties = new Properties();
    SimpleSpanProcessor spanProcessor =
        SimpleSpanProcessor.builder(spanExporter).readProperties(properties).build();

    when(readableSpan.getSpanContext()).thenReturn(NOT_SAMPLED_SPAN_CONTEXT);
    spanProcessor.onEnd(readableSpan);
    verifyNoInteractions(spanExporter);
  }

  @Test
  void buildFromProperties_onlySampledTrue() {
    Properties properties = new Properties();
    properties.setProperty("otel.ssp.export.sampled", "true");
    SimpleSpanProcessor spanProcessor =
        SimpleSpanProcessor.builder(spanExporter).readProperties(properties).build();

    when(readableSpan.getSpanContext()).thenReturn(NOT_SAMPLED_SPAN_CONTEXT);
    spanProcessor.onEnd(readableSpan);
    verifyNoInteractions(spanExporter);
  }

  @Test
  void buildFromProperties_onlySampledFalse() {
    Properties properties = new Properties();
    properties.setProperty("otel.ssp.export.sampled", "false");
    SimpleSpanProcessor spanProcessor =
        SimpleSpanProcessor.builder(spanExporter).readProperties(properties).build();
    SpanData spanData = TestUtils.makeBasicSpan();

    when(readableSpan.getSpanContext()).thenReturn(NOT_SAMPLED_SPAN_CONTEXT);
    when(readableSpan.toSpanData()).thenReturn(spanData);

    spanProcessor.onEnd(readableSpan);
    verify(spanExporter).export(Collections.singletonList(spanData));
  }
}
