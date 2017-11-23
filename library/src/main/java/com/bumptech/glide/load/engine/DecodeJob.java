package com.bumptech.glide.load.engine;

import android.os.Build;
import android.os.Trace;
import android.support.annotation.NonNull;
import android.support.v4.os.TraceCompat;
import android.support.v4.util.Pools;
import android.util.Log;
import com.bumptech.glide.GlideContext;
import com.bumptech.glide.Priority;
import com.bumptech.glide.Registry;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.EncodeStrategy;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceEncoder;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.data.DataRewinder;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.resource.bitmap.Downsampler;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.pool.FactoryPools.Poolable;
import com.bumptech.glide.util.pool.StateVerifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A class responsible for decoding resources either from cached data or from the original source
 * and applying transformations and transcodes.
 *
 * <p>Note: this class has a natural ordering that is inconsistent with equals.
 *
 * @param <R> The type of resource that will be transcoded from the decoded and transformed
 *            resource.
 */
class DecodeJob<R> implements DataFetcherGenerator.FetcherReadyCallback,
    Runnable,
    Comparable<DecodeJob<?>>,
    Poolable {
  private static final String TAG = "DecodeJob";

  @SuppressWarnings("WeakerAccess")
  @Synthetic
  final DecodeHelper<R> decodeHelper = new DecodeHelper<>();
  private final List<Throwable> throwables = new ArrayList<>();
  private final StateVerifier stateVerifier = StateVerifier.newInstance();
  private final DiskCacheProvider diskCacheProvider;
  private final Pools.Pool<DecodeJob<?>> pool;
  @SuppressWarnings("WeakerAccess")
  @Synthetic
  final DeferredEncodeManager<?> deferredEncodeManager = new DeferredEncodeManager<>();
  private final ReleaseManager releaseManager = new ReleaseManager();

  private GlideContext glideContext;
  @SuppressWarnings("WeakerAccess") @Synthetic Key signature;
  private Priority priority;
  private EngineKey loadKey;
  @SuppressWarnings("WeakerAccess") @Synthetic int width;
  @SuppressWarnings("WeakerAccess") @Synthetic int height;
  @SuppressWarnings("WeakerAccess") @Synthetic DiskCacheStrategy diskCacheStrategy;
  @SuppressWarnings("WeakerAccess") @Synthetic Options options;
  private Callback<R> callback;
  private int order;
  private Stage stage;
  private RunReason runReason;
  private long startFetchTime;
  private boolean onlyRetrieveFromCache;

  private Thread currentThread;
  @SuppressWarnings("WeakerAccess") @Synthetic Key currentSourceKey;
  private Key currentAttemptingKey;
  private Object currentData;
  private DataSource currentDataSource;
  private DataFetcher<?> currentFetcher;

  private volatile DataFetcherGenerator currentGenerator;
  private volatile boolean isCallbackNotified;
  private volatile boolean isCancelled;

  DecodeJob(DiskCacheProvider diskCacheProvider, Pools.Pool<DecodeJob<?>> pool) {
    this.diskCacheProvider = diskCacheProvider;
    this.pool = pool;
  }

  DecodeJob<R> init(
      GlideContext glideContext,
      Object model,
      EngineKey loadKey,
      Key signature,
      int width,
      int height,
      Class<?> resourceClass,
      Class<R> transcodeClass,
      Priority priority,
      DiskCacheStrategy diskCacheStrategy,
      Map<Class<?>, Transformation<?>> transformations,
      boolean isTransformationRequired,
      boolean isScaleOnlyOrNoTransform,
      boolean onlyRetrieveFromCache,
      Options options,
      Callback<R> callback,
      int order) {
    decodeHelper.init(
        glideContext,
        model,
        signature,
        width,
        height,
        diskCacheStrategy,
        resourceClass,
        transcodeClass,
        priority,
        options,
        transformations,
        isTransformationRequired,
        isScaleOnlyOrNoTransform,
        diskCacheProvider);
    this.glideContext = glideContext;
    this.signature = signature;
    this.priority = priority;
    this.loadKey = loadKey;
    this.width = width;
    this.height = height;
    this.diskCacheStrategy = diskCacheStrategy;
    this.onlyRetrieveFromCache = onlyRetrieveFromCache;
    this.options = options;
    this.callback = callback;
    this.order = order;
    this.runReason = RunReason.INITIALIZE;
    return this;
  }

  /**
   * Returns true if this job will attempt to decode a resource from the disk cache, and false if it
   * will always decode from source.
   */
  boolean willDecodeFromCache() {
    Stage firstStage = getNextStage(Stage.INITIALIZE);
    return firstStage == Stage.RESOURCE_CACHE || firstStage == Stage.DATA_CACHE;
  }

  /**
   * Called when this object is no longer in use externally.
   *
   * @param isRemovedFromQueue {@code true} if we've been removed from the queue and {@link #run}
   *                           is neither in progress nor will ever be called again.
   */
  void release(boolean isRemovedFromQueue) {
    if (releaseManager.release(isRemovedFromQueue)) {
      releaseInternal();
    }
  }

  /**
   * Called when we've finished encoding (either because the encode process is complete, or because
   * we don't have anything to encode).
   */
  private void onEncodeComplete() {
    if (releaseManager.onEncodeComplete()) {
      releaseInternal();
    }
  }

  /**
   * Called when the load has failed due to a an error or a series of errors.
   */
  private void onLoadFailed() {
    if (releaseManager.onFailed()) {
      releaseInternal();
    }
  }

  private void releaseInternal() {
    releaseManager.reset();
    deferredEncodeManager.clear();
    decodeHelper.clear();
    isCallbackNotified = false;
    glideContext = null;
    signature = null;
    options = null;
    priority = null;
    loadKey = null;
    callback = null;
    stage = null;
    currentGenerator = null;
    currentThread = null;
    currentSourceKey = null;
    currentData = null;
    currentDataSource = null;
    currentFetcher = null;
    startFetchTime = 0L;
    isCancelled = false;
    throwables.clear();
    pool.release(this);
  }

  @Override
  public int compareTo(@NonNull DecodeJob<?> other) {
    int result = getPriority() - other.getPriority();
    if (result == 0) {
      result = order - other.order;
    }
    return result;
  }

  private int getPriority() {
    return priority.ordinal();
  }

  public void cancel() {
    isCancelled = true;
    DataFetcherGenerator local = currentGenerator;
    if (local != null) {
      local.cancel();
    }
  }

  //wsq GlideExecutor真正启动的方法在这里
  @Override
  public void run() {
    // This should be much more fine grained, but since Java's thread pool implementation silently
    // swallows all otherwise fatal exceptions, this will at least make it obvious to developers
    // that something is failing.
    TraceCompat.beginSection("DecodeJob#run");
    // Methods in the try statement can invalidate currentFetcher, so set a local variable here to
    // ensure that the fetcher is cleaned up either way.

    //wsq 使用DataFetcher来获取数据
    DataFetcher<?> localFetcher = currentFetcher;
    try {
      if (isCancelled) {
        notifyFailed();
        return;
      }
      runWrapped();
    } catch (Throwable t) {
      // Catch Throwable and not Exception to handle OOMs. Throwables are swallowed by our
      // usage of .submit() in GlideExecutor so we're not silently hiding crashes by doing this. We
      // are however ensuring that our callbacks are always notified when a load fails. Without this
      // notification, uncaught throwables never notify the corresponding callbacks, which can cause
      // loads to silently hang forever, a case that's especially bad for users using Futures on
      // background threads.
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, "DecodeJob threw unexpectedly"
            + ", isCancelled: " + isCancelled
            + ", stage: " + stage, t);
      }
      // When we're encoding we've already notified our callback and it isn't safe to do so again.
      if (stage != Stage.ENCODE) {
        throwables.add(t);
        notifyFailed();
      }
      if (!isCancelled) {
        throw t;
      }
    } finally {
      // Keeping track of the fetcher here and calling cleanup is excessively paranoid, we call
      // close in all cases anyway.
      if (localFetcher != null) {
        localFetcher.cleanup();
      }
      TraceCompat.endSection();
    }
  }

  private void runWrapped() {
     switch (runReason) {
      case INITIALIZE:
        stage = getNextStage(Stage.INITIALIZE);
        currentGenerator = getNextGenerator();
        runGenerators();
        break;
      case SWITCH_TO_SOURCE_SERVICE:
        runGenerators();
        break;
      case DECODE_DATA:
        decodeFromRetrievedData();
        break;
      default:
        throw new IllegalStateException("Unrecognized run reason: " + runReason);
    }
  }

  private DataFetcherGenerator getNextGenerator() {
    switch (stage) {
      case RESOURCE_CACHE:
        return new ResourceCacheGenerator(decodeHelper, this);
      case DATA_CACHE:
        return new DataCacheGenerator(decodeHelper, this);
      case SOURCE:
        return new SourceGenerator(decodeHelper, this);
      case FINISHED:
        return null;
      default:
        throw new IllegalStateException("Unrecognized stage: " + stage);
    }
  }

  private void runGenerators() {
    currentThread = Thread.currentThread();
    startFetchTime = LogTime.getLogTime();
    boolean isStarted = false;
    while (!isCancelled && currentGenerator != null
        && !(isStarted = currentGenerator.startNext())) {
      stage = getNextStage(stage);
      currentGenerator = getNextGenerator();

      if (stage == Stage.SOURCE) {
        //wsq重新开始任务
        reschedule();
        return;
      }
    }
    // We've run out of stages and generators, give up.
    if ((stage == Stage.FINISHED || isCancelled) && !isStarted) {
      notifyFailed();
    }

    // Otherwise a generator started a new load and we expect to be called back in
    // onDataFetcherReady.
  }

  private void notifyFailed() {
    setNotifiedOrThrow();
    GlideException e = new GlideException("Failed to load resource", new ArrayList<>(throwables));
    callback.onLoadFailed(e);
    onLoadFailed();
  }

  private void notifyComplete(Resource<R> resource, DataSource dataSource) {
    setNotifiedOrThrow();
    callback.onResourceReady(resource, dataSource);
  }

  private void setNotifiedOrThrow() {
    stateVerifier.throwIfRecycled();
    if (isCallbackNotified) {
      throw new IllegalStateException("Already notified");
    }
    isCallbackNotified = true;
  }

  //wsq stage变化 initialize -> RESOURCE_CACHE -> DATA_CACHE -> (ENCODE) ->SOURCE | FINISHED;
  private Stage getNextStage(Stage current) {
    switch (current) {
      case INITIALIZE:
        return diskCacheStrategy.decodeCachedResource()
            ? Stage.RESOURCE_CACHE : getNextStage(Stage.RESOURCE_CACHE);
      case RESOURCE_CACHE:
        return diskCacheStrategy.decodeCachedData()
            ? Stage.DATA_CACHE : getNextStage(Stage.DATA_CACHE);
      case DATA_CACHE:
        // Skip loading from source if the user opted to only retrieve the resource from cache.
        return onlyRetrieveFromCache ? Stage.FINISHED : Stage.SOURCE;
      case SOURCE:
      case FINISHED:
        return Stage.FINISHED;
      default:
        throw new IllegalArgumentException("Unrecognized stage: " + current);
    }
  }

  @Override
  public void reschedule() {
    runReason = RunReason.SWITCH_TO_SOURCE_SERVICE;
    callback.reschedule(this);
  }

  //获取资源之后回调
  @Override
  public void onDataFetcherReady(Key sourceKey, Object data, DataFetcher<?> fetcher,
      DataSource dataSource, Key attemptedKey) {
    this.currentSourceKey = sourceKey;
    this.currentData = data;
    this.currentFetcher = fetcher;
    this.currentDataSource = dataSource;
    this.currentAttemptingKey = attemptedKey;
    if (Thread.currentThread() != currentThread) {
      runReason = RunReason.DECODE_DATA;
      callback.reschedule(this);
    } else {
      TraceCompat.beginSection("DecodeJob.decodeFromRetrievedData");
      try {
        decodeFromRetrievedData();
      } finally {
        TraceCompat.endSection();
      }
    }
  }

  @Override
  public void onDataFetcherFailed(Key attemptedKey, Exception e, DataFetcher<?> fetcher,
      DataSource dataSource) {
    fetcher.cleanup();
    GlideException exception = new GlideException("Fetching data failed", e);
    exception.setLoggingDetails(attemptedKey, dataSource, fetcher.getDataClass());
    throwables.add(exception);
    if (Thread.currentThread() != currentThread) {
      //wsq 判断当前线程是否被回收
      runReason = RunReason.SWITCH_TO_SOURCE_SERVICE;
      callback.reschedule(this);
    } else {
      runGenerators();
    }
  }

  private void decodeFromRetrievedData() {
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      logWithTimeAndKey("Retrieved data", startFetchTime,
          "data: " + currentData
          + ", cache key: " + currentSourceKey
          + ", fetcher: " + currentFetcher);
    }
    Resource<R> resource = null;
    try {
      resource = decodeFromData(currentFetcher, currentData, currentDataSource);
    } catch (GlideException e) {
      e.setLoggingDetails(currentAttemptingKey, currentDataSource);
      throwables.add(e);
    }
    if (resource != null) {
      notifyEncodeAndRelease(resource, currentDataSource);
    } else {
      runGenerators();
    }
  }

  private void notifyEncodeAndRelease(Resource<R> resource, DataSource dataSource) {
    if (resource instanceof Initializable) {
      ((Initializable) resource).initialize();
    }

    Resource<R> result = resource;
    LockedResource<R> lockedResource = null;
    if (deferredEncodeManager.hasResourceToEncode()) {
      lockedResource = LockedResource.obtain(resource);
      result = lockedResource;
    }

    notifyComplete(result, dataSource);

    stage = Stage.ENCODE;
    try {
      if (deferredEncodeManager.hasResourceToEncode()) {
        deferredEncodeManager.encode(diskCacheProvider, options);
      }
    } finally {
      if (lockedResource != null) {
        lockedResource.unlock();
      }
      onEncodeComplete();
    }
  }

  private <Data> Resource<R> decodeFromData(DataFetcher<?> fetcher, Data data,
      DataSource dataSource) throws GlideException {
    try {
      if (data == null) {
        return null;
      }
      long startTime = LogTime.getLogTime();
      //wsq 获取数据
      Resource<R> result = decodeFromFetcher(data, dataSource);
      if (Log.isLoggable(TAG, Log.VERBOSE)) {
        logWithTimeAndKey("Decoded result " + result, startTime);
      }
      return result;
    } finally {
      fetcher.cleanup();
    }
  }

  @SuppressWarnings("unchecked")
  private <Data> Resource<R> decodeFromFetcher(Data data, DataSource dataSource)
      throws GlideException {
    LoadPath<Data, ?, R> path = decodeHelper.getLoadPath((Class<Data>) data.getClass());
    return runLoadPath(data, dataSource, path);
  }

  private Options getOptionsWithHardwareConfig(DataSource dataSource) {
    Options options = this.options;
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return options;
    }

    if (options.get(Downsampler.ALLOW_HARDWARE_CONFIG) != null) {
      return options;
    }

    if (dataSource == DataSource.RESOURCE_DISK_CACHE
        || decodeHelper.isScaleOnlyOrNoTransform()) {
      options = new Options();
      options.putAll(this.options);
      options.set(Downsampler.ALLOW_HARDWARE_CONFIG, true);
    }
    return options;
  }

  private <Data, ResourceType> Resource<R> runLoadPath(Data data, DataSource dataSource,
      LoadPath<Data, ResourceType, R> path) throws GlideException {
    Options options = getOptionsWithHardwareConfig(dataSource);
    DataRewinder<Data> rewinder = glideContext.getRegistry().getRewinder(data);
    try {
      // ResourceType in DecodeCallback below is required for compilation to work with gradle.
      return path.load(
          rewinder, options, width, height, new DecodeCallback<ResourceType>(dataSource));
    } finally {
      rewinder.cleanup();
    }
  }

  private void logWithTimeAndKey(String message, long startTime) {
    logWithTimeAndKey(message, startTime, null /*extraArgs*/);
  }

  private void logWithTimeAndKey(String message, long startTime, String extraArgs) {
    Log.v(TAG, message + " in " + LogTime.getElapsedMillis(startTime) + ", load key: " + loadKey
        + (extraArgs != null ? ", " + extraArgs : "") + ", thread: "
        + Thread.currentThread().getName());
  }

  @Override
  public StateVerifier getVerifier() {
    return stateVerifier;
  }

  private final class DecodeCallback<Z> implements DecodePath.DecodeCallback<Z> {

    private final DataSource dataSource;

    @Synthetic
    DecodeCallback(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    @Override
    public Resource<Z> onResourceDecoded(Resource<Z> decoded) {
      Class<Z> resourceSubClass = getResourceClass(decoded);
      Transformation<Z> appliedTransformation = null;
      Resource<Z> transformed = decoded;
      if (dataSource != DataSource.RESOURCE_DISK_CACHE) {
        appliedTransformation = decodeHelper.getTransformation(resourceSubClass);
        transformed = appliedTransformation.transform(glideContext, decoded, width, height);
      }
      // TODO: Make this the responsibility of the Transformation.
      if (!decoded.equals(transformed)) {
        decoded.recycle();
      }

      final EncodeStrategy encodeStrategy;
      final ResourceEncoder<Z> encoder;
      if (decodeHelper.isResourceEncoderAvailable(transformed)) {
        encoder = decodeHelper.getResultEncoder(transformed);
        encodeStrategy = encoder.getEncodeStrategy(options);
      } else {
        encoder = null;
        encodeStrategy = EncodeStrategy.NONE;
      }

      Resource<Z> result = transformed;
      boolean isFromAlternateCacheKey = !decodeHelper.isSourceKey(currentSourceKey);
      if (diskCacheStrategy.isResourceCacheable(isFromAlternateCacheKey, dataSource,
          encodeStrategy)) {
        if (encoder == null) {
          throw new Registry.NoResultEncoderAvailableException(transformed.get().getClass());
        }
        final Key key;
        if (encodeStrategy == EncodeStrategy.SOURCE) {
          key = new DataCacheKey(currentSourceKey, signature);
        } else if (encodeStrategy == EncodeStrategy.TRANSFORMED) {
          key = new ResourceCacheKey(currentSourceKey, signature, width, height,
              appliedTransformation, resourceSubClass, options);
        } else {
          throw new IllegalArgumentException("Unknown strategy: " + encodeStrategy);
        }

        LockedResource<Z> lockedResult = LockedResource.obtain(transformed);
        deferredEncodeManager.init(key, encoder, lockedResult);
        result = lockedResult;
      }
      return result;
    }

    @SuppressWarnings("unchecked")
    private Class<Z> getResourceClass(Resource<Z> resource) {
      return (Class<Z>) resource.get().getClass();
    }
  }

  /**
   * Responsible for indicating when it is safe for the job to be cleared and returned to the pool.
   */
  private static class ReleaseManager {
    private boolean isReleased;
    private boolean isEncodeComplete;
    private boolean isFailed;

    @Synthetic
    ReleaseManager() { }

    synchronized boolean release(boolean isRemovedFromQueue) {
      isReleased = true;
      return isComplete(isRemovedFromQueue);
    }

    synchronized boolean onEncodeComplete() {
      isEncodeComplete = true;
      return isComplete(false /*isRemovedFromQueue*/);
    }

    synchronized boolean onFailed() {
      isFailed = true;
      return isComplete(false /*isRemovedFromQueue*/);
    }

    synchronized void reset() {
      isEncodeComplete = false;
      isReleased = false;
      isFailed = false;
    }

    private boolean isComplete(boolean isRemovedFromQueue) {
      return (isFailed || isRemovedFromQueue || isEncodeComplete) && isReleased;
    }
  }

  /**
   * Allows transformed resources to be encoded after the transcoded result is already delivered
   * to requestors.
   */
  private static class DeferredEncodeManager<Z> {
    private Key key;
    private ResourceEncoder<Z> encoder;
    private LockedResource<Z> toEncode;

    @Synthetic
    DeferredEncodeManager() { }

    // We just need the encoder and resource type to match, which this will enforce.
    @SuppressWarnings("unchecked")
    <X> void init(Key key, ResourceEncoder<X> encoder, LockedResource<X> toEncode) {
      this.key = key;
      this.encoder = (ResourceEncoder<Z>) encoder;
      this.toEncode = (LockedResource<Z>) toEncode;
    }

    void encode(DiskCacheProvider diskCacheProvider, Options options) {
      TraceCompat.beginSection("DecodeJob.encode");
      try {
        diskCacheProvider.getDiskCache().put(key,
            new DataCacheWriter<>(encoder, toEncode, options));
      } finally {
        toEncode.unlock();
        TraceCompat.endSection();
      }
    }

    boolean hasResourceToEncode() {
      return toEncode != null;
    }

    void clear() {
      key = null;
      encoder = null;
      toEncode = null;
    }
  }

  interface Callback<R> {

    void onResourceReady(Resource<R> resource, DataSource dataSource);

    void onLoadFailed(GlideException e);

    void reschedule(DecodeJob<?> job);
  }

  interface DiskCacheProvider {
    DiskCache getDiskCache();
  }

  /**
   * Why we're being executed again.
   */
  private enum RunReason {
    /** The first time we've been submitted. */
    INITIALIZE,
    /**
     * We want to switch from the disk cache service to the source executor.
     */
    SWITCH_TO_SOURCE_SERVICE,
    /**
     * We retrieved some data on a thread we don't own and want to switch back to our thread to
     * process the data.
     */
    DECODE_DATA,
  }

  /**
   * Where we're trying to decode data from.
   */
  private enum Stage {
    /** The initial stage. */
    INITIALIZE,
    /** Decode from a cached resource. */
    RESOURCE_CACHE,
    /** Decode from cached source data. */
    DATA_CACHE,
    /** Decode from retrieved source. */
    SOURCE,
    /** Encoding transformed resources after a successful load. */
    ENCODE,
    /** No more viable stages. */
    FINISHED,
  }
}
