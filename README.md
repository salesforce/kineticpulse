# kineticpulse

[![CircleCI](https://circleci.com/gh/salesforce/kineticpulse.svg?style=svg)](https://circleci.com/gh/salesforce/kineticpulse)

Collecting metrics on a Scala Play service client for monitoring.  This library 
covers the metrics collection via Prometheus client types such as Counter, Summary. 
A metrics server can scrape the collected metrics via a specified HTTP endpoint.  

## Client metrics collection 

### Basic metrics 
- http_requests_total
- http_requests_percentile_seconds

From `io.prometheus simpleclient_hotspot` library
- jvm_* metrics
- process_* metrics

### More focused metrics
These 2 metrics can be configured to have more detailed and focused request count and
timing percentiles via the `routes-to-track` configuration.  See example below.  
- api_requests_total
- api_duration_seconds_summary

## build.sbt libraryDependencies
```
"com.salesforce.mce" %% "kineticpulse-metric" % "0.0.1"
```
## Configuration
Specify a configuration at `application.conf` to override settings in the 
`reference.conf` of the `kineticpulse` library.

Example
```
com.salesforce.mce.kineticpulse.metrics {

    # User configurable metric endpoint at [endpoint].
    # If endpoint is null or empty string, metrics will not be collected
    endpoint = "__metrics"

    # some http request paths are not interesting to capture metrics such as http duration
    # Disable metric collection for these request paths
    bypass.paths = ["/__status", "/", "/__metrics"]

    # routes segments to track in api_duration_seconds_summary, matched values go to the metric label "path"
    # example:  ["api/v1", "model/predict", "model/accuracy"]
    routes-to-track = ["spotme/latest"]

    # delimiter is used within metric label "path" to replace the "/" in the request.path
    # See example where spotme/latest is changed to spotme-latest
    delimiter = "-"

    # label value to be used when a path is not bypass, but did not match routes-to-track,
    unmatched-path = ""
}
```

### routes-to-track
Suppose `routes-to-track = ["spotme", "spotme/latest"]` is configured.  Consider an example end-point
GET http://my.example.com/api/v1/spotme/latest/app, both `spotme` and `spotme-latest`are matched 
for the resulting metrics having a row each of labels with the `path` key.

With another GET http://my.example.com/api/v1/spotme/green/app, which matches `spotme` though not
`spotme/latest`, the following would be in the `__metrics` payload

```text
api_requests_total{status="200",method="GET",path="spotme",arguments="",} 2.0
api_requests_total{status="200",method="GET",path="spotme-latest",arguments="",} 1.0
```
Similarly, for `api_duration_seconds_summary`.

The current implementation does not track label values for `arguments` to optimize the 
number of time series to be created since higher cardinality of the labels results in more 
time series.  The library wants to control the memory and compute load for collecting 
the metrics.  Please customize for different requirements as needed.

## Add DI in Module
In Play app/Module.scala, add
```
bind(classOf[Metric]).to(classOf[PrometheusMetric])
```

## Add Filter 
If not already existing, add an app/Filters.scala class.
```
import com.salesforce.mce.kineticpulse.MetricFilter
class Filters @Inject() (
  metricFilter: MetricFilter
) extends DefaultHttpFilters(metricFilter)
```

## Add route for metrics scrape 
In play app/conf/routes, add endpoint such as `__metrics` to invoke `com.salesforce.mce.kineticpulse.MetricController.collect`

## Customize further

If needed, extend the `Metric` or `PrometheusMetric` class to override the existing behavior. 

For other custom metrics, we suggest to introducing a new Scala object to define and register 
additional Prometheus client types under the default registry, and to add collect logic where 
needed.  The `MetricController` will include such custom metrics from the default registry
to serialize in Prometheus client format for metrics scrape.  

### Example payload at `__metrics` endpoint
```
# HELP http_requests_total Total HTTP Requests Count
# TYPE http_requests_total counter
http_requests_total{status="200",method="GET",} 3.0
# HELP http_requests_percentile_seconds Total HTTP Requests Duration Percentile in seconds
# TYPE http_requests_percentile_seconds summary
http_requests_percentile_seconds{quantile="0.5",} 1.4882145
http_requests_percentile_seconds{quantile="0.95",} 3.5854054
http_requests_percentile_seconds{quantile="0.99",} 3.5854054
http_requests_percentile_seconds_count 3.0
http_requests_percentile_seconds_sum 6.4634309
# HELP api_requests_total Total API HTTP Requests Count
# TYPE api_requests_total counter
api_requests_total{status="200",method="GET",path="spotme-latest",arguments="",} 3.0
# HELP api_duration_seconds_summary Profile API response time in seconds summary
# TYPE api_duration_seconds_summary summary
api_duration_seconds_summary{method="GET",path="spotme-latest",arguments="",quantile="0.5",} 1.4882051
api_duration_seconds_summary{method="GET",path="spotme-latest",arguments="",quantile="0.95",} 3.5853817
api_duration_seconds_summary{method="GET",path="spotme-latest",arguments="",quantile="0.99",} 3.5853817
api_duration_seconds_summary_count{method="GET",path="spotme-latest",arguments="",} 3.0
api_duration_seconds_summary_sum{method="GET",path="spotme-latest",arguments="",} 6.463391
# HELP jvm_buffer_pool_used_bytes Used bytes of a given JVM buffer pool.
# TYPE jvm_buffer_pool_used_bytes gauge
jvm_buffer_pool_used_bytes{pool="direct",} 1.409438E7
jvm_buffer_pool_used_bytes{pool="mapped",} 0.0
# HELP jvm_buffer_pool_capacity_bytes Bytes capacity of a given JVM buffer pool.
# TYPE jvm_buffer_pool_capacity_bytes gauge
jvm_buffer_pool_capacity_bytes{pool="direct",} 1.409438E7
jvm_buffer_pool_capacity_bytes{pool="mapped",} 0.0
# HELP jvm_buffer_pool_used_buffers Used buffers of a given JVM buffer pool.
# TYPE jvm_buffer_pool_used_buffers gauge
jvm_buffer_pool_used_buffers{pool="direct",} 16.0
jvm_buffer_pool_used_buffers{pool="mapped",} 0.0
# HELP jvm_gc_collection_seconds Time spent in a given JVM garbage collector in seconds.
# TYPE jvm_gc_collection_seconds summary
jvm_gc_collection_seconds_count{gc="PS Scavenge",} 2393.0
jvm_gc_collection_seconds_sum{gc="PS Scavenge",} 10.263
jvm_gc_collection_seconds_count{gc="PS MarkSweep",} 6.0
jvm_gc_collection_seconds_sum{gc="PS MarkSweep",} 0.684
# HELP jvm_memory_objects_pending_finalization The number of objects waiting in the finalizer queue.
# TYPE jvm_memory_objects_pending_finalization gauge
jvm_memory_objects_pending_finalization 0.0
# HELP jvm_memory_bytes_used Used bytes of a given JVM memory area.
# TYPE jvm_memory_bytes_used gauge
jvm_memory_bytes_used{area="heap",} 6.77809128E8
jvm_memory_bytes_used{area="nonheap",} 3.79781192E8
# HELP jvm_memory_bytes_committed Committed (bytes) of a given JVM memory area.
# TYPE jvm_memory_bytes_committed gauge
jvm_memory_bytes_committed{area="heap",} 1.668284416E9
jvm_memory_bytes_committed{area="nonheap",} 4.30465024E8
# HELP jvm_memory_bytes_max Max (bytes) of a given JVM memory area.
# TYPE jvm_memory_bytes_max gauge
jvm_memory_bytes_max{area="heap",} 1.854930944E9
jvm_memory_bytes_max{area="nonheap",} -1.0
# HELP jvm_memory_bytes_init Initial bytes of a given JVM memory area.
# TYPE jvm_memory_bytes_init gauge
jvm_memory_bytes_init{area="heap",} 5.36870912E8
jvm_memory_bytes_init{area="nonheap",} 2555904.0
# HELP jvm_memory_pool_bytes_used Used bytes of a given JVM memory pool.
# TYPE jvm_memory_pool_bytes_used gauge
jvm_memory_pool_bytes_used{pool="Code Cache",} 9.9252928E7
jvm_memory_pool_bytes_used{pool="Metaspace",} 2.48641888E8
jvm_memory_pool_bytes_used{pool="Compressed Class Space",} 3.1886376E7
jvm_memory_pool_bytes_used{pool="PS Eden Space",} 5.28267192E8
jvm_memory_pool_bytes_used{pool="PS Survivor Space",} 65536.0
jvm_memory_pool_bytes_used{pool="PS Old Gen",} 1.49485176E8
# HELP jvm_memory_pool_bytes_committed Committed bytes of a given JVM memory pool.
# TYPE jvm_memory_pool_bytes_committed gauge
jvm_memory_pool_bytes_committed{pool="Code Cache",} 1.14098176E8
jvm_memory_pool_bytes_committed{pool="Metaspace",} 2.824192E8
jvm_memory_pool_bytes_committed{pool="Compressed Class Space",} 3.3947648E7
jvm_memory_pool_bytes_committed{pool="PS Eden Space",} 6.94157312E8
jvm_memory_pool_bytes_committed{pool="PS Survivor Space",} 524288.0
jvm_memory_pool_bytes_committed{pool="PS Old Gen",} 9.73602816E8
# HELP jvm_memory_pool_bytes_max Max bytes of a given JVM memory pool.
# TYPE jvm_memory_pool_bytes_max gauge
jvm_memory_pool_bytes_max{pool="Code Cache",} 2.5165824E8
jvm_memory_pool_bytes_max{pool="Metaspace",} -1.0
jvm_memory_pool_bytes_max{pool="Compressed Class Space",} 1.073741824E9
jvm_memory_pool_bytes_max{pool="PS Eden Space",} 6.94157312E8
jvm_memory_pool_bytes_max{pool="PS Survivor Space",} 524288.0
jvm_memory_pool_bytes_max{pool="PS Old Gen",} 1.391460352E9
# HELP jvm_memory_pool_bytes_init Initial bytes of a given JVM memory pool.
# TYPE jvm_memory_pool_bytes_init gauge
jvm_memory_pool_bytes_init{pool="Code Cache",} 2555904.0
jvm_memory_pool_bytes_init{pool="Metaspace",} 0.0
jvm_memory_pool_bytes_init{pool="Compressed Class Space",} 0.0
jvm_memory_pool_bytes_init{pool="PS Eden Space",} 1.34742016E8
jvm_memory_pool_bytes_init{pool="PS Survivor Space",} 2.2020096E7
jvm_memory_pool_bytes_init{pool="PS Old Gen",} 3.58088704E8
# HELP jvm_memory_pool_collection_used_bytes Used bytes after last collection of a given JVM memory pool.
# TYPE jvm_memory_pool_collection_used_bytes gauge
jvm_memory_pool_collection_used_bytes{pool="PS Eden Space",} 0.0
jvm_memory_pool_collection_used_bytes{pool="PS Survivor Space",} 65536.0
jvm_memory_pool_collection_used_bytes{pool="PS Old Gen",} 1.4569344E8
# HELP jvm_memory_pool_collection_committed_bytes Committed after last collection bytes of a given JVM memory pool.
# TYPE jvm_memory_pool_collection_committed_bytes gauge
jvm_memory_pool_collection_committed_bytes{pool="PS Eden Space",} 6.94157312E8
jvm_memory_pool_collection_committed_bytes{pool="PS Survivor Space",} 524288.0
jvm_memory_pool_collection_committed_bytes{pool="PS Old Gen",} 9.73602816E8
# HELP jvm_memory_pool_collection_max_bytes Max bytes after last collection of a given JVM memory pool.
# TYPE jvm_memory_pool_collection_max_bytes gauge
jvm_memory_pool_collection_max_bytes{pool="PS Eden Space",} 6.94157312E8
jvm_memory_pool_collection_max_bytes{pool="PS Survivor Space",} 524288.0
jvm_memory_pool_collection_max_bytes{pool="PS Old Gen",} 1.391460352E9
# HELP jvm_memory_pool_collection_init_bytes Initial after last collection bytes of a given JVM memory pool.
# TYPE jvm_memory_pool_collection_init_bytes gauge
jvm_memory_pool_collection_init_bytes{pool="PS Eden Space",} 1.34742016E8
jvm_memory_pool_collection_init_bytes{pool="PS Survivor Space",} 2.2020096E7
jvm_memory_pool_collection_init_bytes{pool="PS Old Gen",} 3.58088704E8
# HELP jvm_memory_pool_allocated_bytes_total Total bytes allocated in a given JVM memory pool. Only updated after GC, not continuously.
# TYPE jvm_memory_pool_allocated_bytes_total counter
jvm_memory_pool_allocated_bytes_total{pool="Code Cache",} 1.0100448E8
jvm_memory_pool_allocated_bytes_total{pool="PS Eden Space",} 2.95168309768E11
jvm_memory_pool_allocated_bytes_total{pool="PS Old Gen",} 2.83409672E8
jvm_memory_pool_allocated_bytes_total{pool="PS Survivor Space",} 1.3836392E7
jvm_memory_pool_allocated_bytes_total{pool="Compressed Class Space",} 3.19352E7
jvm_memory_pool_allocated_bytes_total{pool="Metaspace",} 2.48784576E8
# HELP jvm_classes_currently_loaded The number of classes that are currently loaded in the JVM
# TYPE jvm_classes_currently_loaded gauge
jvm_classes_currently_loaded 34380.0
# HELP jvm_classes_loaded_total The total number of classes that have been loaded since the JVM has started execution
# TYPE jvm_classes_loaded_total counter
jvm_classes_loaded_total 35311.0
# HELP jvm_classes_unloaded_total The total number of classes that have been unloaded since the JVM has started execution
# TYPE jvm_classes_unloaded_total counter
jvm_classes_unloaded_total 931.0
# HELP jvm_threads_current Current thread count of a JVM
# TYPE jvm_threads_current gauge
jvm_threads_current 59.0
# HELP jvm_threads_daemon Daemon thread count of a JVM
# TYPE jvm_threads_daemon gauge
jvm_threads_daemon 32.0
# HELP jvm_threads_peak Peak thread count of a JVM
# TYPE jvm_threads_peak gauge
jvm_threads_peak 71.0
# HELP jvm_threads_started_total Started thread count of a JVM
# TYPE jvm_threads_started_total counter
jvm_threads_started_total 402001.0
# HELP jvm_threads_deadlocked Cycles of JVM-threads that are in deadlock waiting to acquire object monitors or ownable synchronizers
# TYPE jvm_threads_deadlocked gauge
jvm_threads_deadlocked 0.0
# HELP jvm_threads_deadlocked_monitor Cycles of JVM-threads that are in deadlock waiting to acquire object monitors
# TYPE jvm_threads_deadlocked_monitor gauge
jvm_threads_deadlocked_monitor 0.0
# HELP jvm_threads_state Current count of threads by state
# TYPE jvm_threads_state gauge
jvm_threads_state{state="NEW",} 0.0
jvm_threads_state{state="TERMINATED",} 0.0
jvm_threads_state{state="RUNNABLE",} 10.0
jvm_threads_state{state="BLOCKED",} 0.0
jvm_threads_state{state="WAITING",} 34.0
jvm_threads_state{state="TIMED_WAITING",} 16.0
jvm_threads_state{state="UNKNOWN",} 0.0
# HELP process_cpu_seconds_total Total user and system CPU time spent in seconds.
# TYPE process_cpu_seconds_total counter
process_cpu_seconds_total 348.06
# HELP process_start_time_seconds Start time of the process since unix epoch in seconds.
# TYPE process_start_time_seconds gauge
process_start_time_seconds 1.659474199947E9
# HELP process_open_fds Number of open file descriptors.
# TYPE process_open_fds gauge
process_open_fds 350.0
# HELP process_max_fds Maximum number of open file descriptors.
# TYPE process_max_fds gauge
process_max_fds 1048576.0
# HELP process_virtual_memory_bytes Virtual memory size in bytes.
# TYPE process_virtual_memory_bytes gauge
process_virtual_memory_bytes 4.152029184E9
# HELP process_resident_memory_bytes Resident memory size in bytes.
# TYPE process_resident_memory_bytes gauge
process_resident_memory_bytes 1.599746048E9
# HELP jvm_info VM version info
# TYPE jvm_info gauge
jvm_info{runtime="OpenJDK Runtime Environment",vendor="Azul Systems, Inc.",version="1.8.0_302-b08",} 1.0
# HELP api_duration_seconds_summary_created Profile API response time in seconds summary
# TYPE api_duration_seconds_summary_created gauge
api_duration_seconds_summary_created{method="GET",path="spotme-latest",arguments="",} 1.659474412873E9
# HELP api_requests_created Total API HTTP Requests Count
# TYPE api_requests_created gauge
api_requests_created{status="500",method="GET",path="spotme-latest",arguments="",} 1.659474416467E9
# HELP http_request_percentile_seconds_created Total HTTP Requests Duration Percentile in seconds
# TYPE http_request_percentile_seconds_created gauge
http_request_percentile_seconds_created 1.65947441287E9
# HELP http_requests_created Total HTTP Requests Count
# TYPE http_requests_created gauge
http_requests_created{status="500",method="GET",} 1.659474416467E9
# HELP jvm_memory_pool_allocated_bytes_created Total bytes allocated in a given JVM memory pool. Only updated after GC, not continuously.
# TYPE jvm_memory_pool_allocated_bytes_created gauge
jvm_memory_pool_allocated_bytes_created{pool="Code Cache",} 1.659474399643E9
jvm_memory_pool_allocated_bytes_created{pool="PS Eden Space",} 1.659474399635E9
jvm_memory_pool_allocated_bytes_created{pool="PS Old Gen",} 1.659474399643E9
jvm_memory_pool_allocated_bytes_created{pool="PS Survivor Space",} 1.659474399643E9
jvm_memory_pool_allocated_bytes_created{pool="Compressed Class Space",} 1.659474399643E9
jvm_memory_pool_allocated_bytes_created{pool="Metaspace",} 1.659474399643E9
```
