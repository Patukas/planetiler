package com.onthegomap.flatmap.collection;

import com.carrotsearch.hppc.LongLongHashMap;
import com.graphhopper.coll.GHLongLongHashMap;
import com.onthegomap.flatmap.Profile;
import com.onthegomap.flatmap.VectorTile;
import com.onthegomap.flatmap.config.FlatmapConfig;
import com.onthegomap.flatmap.geo.GeometryException;
import com.onthegomap.flatmap.geo.GeometryType;
import com.onthegomap.flatmap.geo.TileCoord;
import com.onthegomap.flatmap.render.RenderedFeature;
import com.onthegomap.flatmap.stats.Stats;
import com.onthegomap.flatmap.util.CommonStringEncoder;
import com.onthegomap.flatmap.util.DiskBacked;
import com.onthegomap.flatmap.util.LayerStats;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.concurrent.NotThreadSafe;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility that accepts rendered map features in any order and groups them by tile for a reader to iterate through.
 * <p>
 * Only support single-threaded writes and reads.
 * <p>
 * Limitation: layer name and attribute key strings get compressed into a single byte, so only 250 unique values are
 * supported (see {@link CommonStringEncoder})
 */
@NotThreadSafe
public final class FeatureGroup implements Consumer<SortableFeature>, Iterable<FeatureGroup.TileFeatures>,
  DiskBacked {

  public static final int Z_ORDER_BITS = 23;
  public static final int Z_ORDER_MAX = (1 << (Z_ORDER_BITS - 1)) - 1;
  public static final int Z_ORDER_MIN = -(1 << (Z_ORDER_BITS - 1));
  private static final int Z_ORDER_MASK = (1 << Z_ORDER_BITS) - 1;
  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureGroup.class);
  private final FeatureSort sorter;
  private final Profile profile;
  private final CommonStringEncoder commonStrings;
  private final Stats stats;
  private final LayerStats layerStats = new LayerStats();

  FeatureGroup(FeatureSort sorter, Profile profile, CommonStringEncoder commonStrings, Stats stats) {
    this.sorter = sorter;
    this.profile = profile;
    this.commonStrings = commonStrings;
    this.stats = stats;
  }

  FeatureGroup(FeatureSort sorter, Profile profile, Stats stats) {
    this(sorter, profile, new CommonStringEncoder(), stats);
  }

  /** Returns a feature grouper that stores all feature in-memory. Only suitable for toy use-cases like unit tests. */
  public static FeatureGroup newInMemoryFeatureGroup(Profile profile, Stats stats) {
    return new FeatureGroup(FeatureSort.newInMemory(), profile, stats);
  }

  /**
   * Returns a feature grouper that writes all elements to disk in chunks, sorts each chunk, then reads back in order
   * from those chunks. Suitable for making maps up to planet-scale.
   */
  public static FeatureGroup newDiskBackedFeatureGroup(Path tempDir, Profile profile, FlatmapConfig config,
    Stats stats) {
    return new FeatureGroup(
      new ExternalMergeSort(tempDir, config, stats),
      profile, stats
    );
  }

  /**
   * Encode sort key by tile asc, layer asc, z-order asc with an extra bit to indicate whether the value contains
   * grouping information.
   */
  static long encodeSortKey(int tile, byte layer, int zOrder, boolean hasGroup) {
    zOrder = -zOrder - 1;
    return ((long) tile << 32L) | ((long) (layer & 0xff) << 24L) | (((zOrder - Z_ORDER_MIN) & Z_ORDER_MASK) << 1L) | (
      hasGroup ? 1 : 0);
  }

  static boolean extractHasGroupFromSortKey(long sortKey) {
    return (sortKey & 1) == 1;
  }

  static int extractTileFromSortKey(long sortKey) {
    return (int) (sortKey >> 32L);
  }

  static byte extractLayerIdFromSortKey(long sortKey) {
    return (byte) (sortKey >> 24);
  }

  static int extractZorderFromKey(long sortKey) {
    return Z_ORDER_MAX - ((int) ((sortKey >> 1) & Z_ORDER_MASK));
  }

  private static RenderedFeature.Group peekAtGroupInfo(byte[] encoded) {
    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)) {
      long group = unpacker.unpackLong();
      int limit = unpacker.unpackInt();
      return new RenderedFeature.Group(group, limit);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Returns statistics about each layer written through {@link #newRenderedFeatureEncoder()} including min/max zoom,
   * features on elements in that layer, and their types.
   */
  public LayerStats layerStats() {
    return layerStats;
  }

  public long numFeaturesWritten() {
    return sorter.numFeaturesWritten();
  }

  /** Returns a function for a single thread to use to serialize rendered features. */
  public Function<RenderedFeature, SortableFeature> newRenderedFeatureEncoder() {
    // This method gets called billions of times when generating the planet, so these optimizations make a big difference:
    // 1) Re-use the same buffer packer to avoid allocating and resizing new byte arrays for every feature.
    var packer = MessagePack.newDefaultBufferPacker();
    // 2) Avoid a ThreadLocal lookup on every layer stats call by getting the handler for this thread once
    var threadLocalLayerStats = layerStats.handlerForThread();

    return new Function<>() {
      // 3) Avoid re-encoding values for identical filled geometries (i.e. ocean) by memoizing the encoded values
      // FeatureRenderer ensures that a separate VectorTileEncoder.Feature is used for each zoom level
      private VectorTile.Feature lastFeature = null;
      private byte[] lastEncodedValue = null;

      @Override
      public SortableFeature apply(RenderedFeature feature) {
        threadLocalLayerStats.accept(feature);
        var group = feature.group().orElse(null);
        var thisFeature = feature.vectorTileFeature();
        byte[] encodedValue;
        if (group != null) { // don't bother memoizing if group is present
          encodedValue = encodeValue(thisFeature, group, packer);
        } else if (lastFeature == thisFeature) {
          encodedValue = lastEncodedValue;
        } else { // feature changed, memoize new value
          lastFeature = thisFeature;
          lastEncodedValue = encodedValue = encodeValue(feature.vectorTileFeature(), null, packer);
        }

        return new SortableFeature(encodeSortKey(feature), encodedValue);
      }
    };
  }

  private long encodeSortKey(RenderedFeature feature) {
    var vectorTileFeature = feature.vectorTileFeature();
    byte encodedLayer = commonStrings.encode(vectorTileFeature.layer());
    return encodeSortKey(
      feature.tile().encoded(),
      encodedLayer,
      feature.zOrder(),
      feature.group().isPresent()
    );
  }

  private byte[] encodeValue(VectorTile.Feature vectorTileFeature, RenderedFeature.Group group,
    MessageBufferPacker packer) {
    packer.clear();
    try {
      // hasGroup bit in key will tell consumers whether they need to decode group info from value
      if (group != null) {
        packer.packLong(group.group());
        packer.packInt(group.limit());
      }
      packer.packLong(vectorTileFeature.id());
      packer.packByte(vectorTileFeature.geometry().geomType().asByte());
      var attrs = vectorTileFeature.attrs();
      packer.packMapHeader((int) attrs.values().stream().filter(Objects::nonNull).count());
      for (Map.Entry<String, Object> entry : attrs.entrySet()) {
        if (entry.getValue() != null) {
          packer.packByte(commonStrings.encode(entry.getKey()));
          Object value = entry.getValue();
          if (value instanceof String string) {
            packer.packValue(ValueFactory.newString(string));
          } else if (value instanceof Integer integer) {
            packer.packValue(ValueFactory.newInteger(integer.longValue()));
          } else if (value instanceof Long longValue) {
            packer.packValue(ValueFactory.newInteger(longValue));
          } else if (value instanceof Float floatValue) {
            packer.packValue(ValueFactory.newFloat(floatValue));
          } else if (value instanceof Double doubleValue) {
            packer.packValue(ValueFactory.newFloat(doubleValue));
          } else if (value instanceof Boolean booleanValue) {
            packer.packValue(ValueFactory.newBoolean(booleanValue));
          } else {
            packer.packValue(ValueFactory.newString(value.toString()));
          }
        }
      }
      // Use the same binary format for encoding geometries in output vector tiles. Benchmarking showed
      // it was faster and smaller for encoding/decoding intermediate geometries than alternatives like WKB.
      int[] commands = vectorTileFeature.geometry().commands();
      packer.packArrayHeader(commands.length);
      for (int command : commands) {
        packer.packInt(command);
      }
      packer.close();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return packer.toByteArray();
  }

  private VectorTile.Feature decodeVectorTileFeature(SortableFeature entry) {
    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(entry.value())) {
      long group;
      if (extractHasGroupFromSortKey(entry.sortKey())) {
        group = unpacker.unpackLong();
        unpacker.unpackInt(); // groupLimit - features over the limit were already discarded
      } else {
        group = VectorTile.Feature.NO_GROUP;
      }
      long id = unpacker.unpackLong();
      byte geomType = unpacker.unpackByte();
      int mapSize = unpacker.unpackMapHeader();
      Map<String, Object> attrs = new HashMap<>(mapSize);
      for (int i = 0; i < mapSize; i++) {
        String key = commonStrings.decode(unpacker.unpackByte());
        Value v = unpacker.unpackValue();
        if (v.isStringValue()) {
          attrs.put(key, v.asStringValue().asString());
        } else if (v.isIntegerValue()) {
          attrs.put(key, v.asIntegerValue().toLong());
        } else if (v.isFloatValue()) {
          attrs.put(key, v.asFloatValue().toDouble());
        } else if (v.isBooleanValue()) {
          attrs.put(key, v.asBooleanValue().getBoolean());
        }
      }
      int commandSize = unpacker.unpackArrayHeader();
      int[] commands = new int[commandSize];
      for (int i = 0; i < commandSize; i++) {
        commands[i] = unpacker.unpackInt();
      }
      String layer = commonStrings.decode(extractLayerIdFromSortKey(entry.sortKey()));
      return new VectorTile.Feature(
        layer,
        id,
        new VectorTile.VectorGeometry(commands, GeometryType.valueOf(geomType)),
        attrs,
        group
      );
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Writes a serialized binary feature to intermediate storage. */
  @Override
  public void accept(SortableFeature entry) {
    sorter.add(entry);
  }

  private volatile boolean prepared = false;

  /** Iterates through features grouped by tile ID. */
  @Override
  public Iterator<TileFeatures> iterator() {
    prepare();
    Iterator<SortableFeature> entries = sorter.iterator();
    if (!entries.hasNext()) {
      return Collections.emptyIterator();
    }

    /*
     * Features from sorter are ordered by tile, so iterate through features as long as
     * they are in the same tile and return that group.
     */
    SortableFeature firstFeature = entries.next();
    return new Iterator<>() {
      private SortableFeature lastFeature = firstFeature;
      private int lastTileId = extractTileFromSortKey(firstFeature.sortKey());

      @Override
      public boolean hasNext() {
        return lastFeature != null;
      }

      @Override
      public TileFeatures next() {
        TileFeatures result = new TileFeatures(lastTileId);
        result.add(lastFeature);
        int lastTile = lastTileId;

        while (entries.hasNext()) {
          SortableFeature next = entries.next();
          lastFeature = next;
          lastTileId = extractTileFromSortKey(lastFeature.sortKey());
          if (lastTile != lastTileId) {
            return result;
          }
          result.add(next);
        }
        lastFeature = null;
        return result;
      }
    };
  }

  @Override
  public long diskUsageBytes() {
    return sorter.diskUsageBytes();
  }

  /** Sorts features to prepare for grouping after all features have been written. */
  public void prepare() {
    if (!prepared) {
      synchronized (this) {
        if (!prepared) {
          sorter.sort();
          prepared = true;
        }
      }
    }
  }

  /** Features contained in a single tile. */
  public class TileFeatures {

    private final TileCoord tileCoord;
    private final List<SortableFeature> entries = new ArrayList<>();
    private final AtomicLong numFeaturesProcessed = new AtomicLong(0);
    private LongLongHashMap counts = null;
    private byte layer = Byte.MAX_VALUE;

    private TileFeatures(int tileCoord) {
      this.tileCoord = TileCoord.decode(tileCoord);
    }

    /** Returns the number of features read including features discarded from being over the limit in a group. */
    public long getNumFeaturesProcessed() {
      return numFeaturesProcessed.get();
    }

    /** Returns the number of features to output, excluding features discarded from being over the limit in a group. */
    public long getNumFeaturesToEmit() {
      return entries.size();
    }

    public TileCoord tileCoord() {
      return tileCoord;
    }

    /**
     * Returns true if {@code other} contains features with identical layer, geometry, and attributes, as this tile -
     * even if the tiles have separate coordinates.
     * <p>
     * Used as an optimization to avoid re-encoding the same ocean tiles over and over again.
     */
    public boolean hasSameContents(TileFeatures other) {
      if (other == null || other.entries.size() != entries.size()) {
        return false;
      }
      for (int i = 0; i < entries.size(); i++) {
        SortableFeature a = entries.get(i);
        SortableFeature b = other.entries.get(i);
        long layerA = extractLayerIdFromSortKey(a.sortKey());
        long layerB = extractLayerIdFromSortKey(b.sortKey());
        if (layerA != layerB || !Arrays.equals(a.value(), b.value())) {
          return false;
        }
      }
      return true;
    }

    public VectorTile getVectorTileEncoder() {
      VectorTile encoder = new VectorTile();
      List<VectorTile.Feature> items = new ArrayList<>(entries.size());
      String currentLayer = null;
      // they were sorted by z-order descending, so we could limit to only the top items, so reverse them now
      for (int index = entries.size() - 1; index >= 0; index--) {
        SortableFeature entry = entries.get(index);

        var feature = decodeVectorTileFeature(entry);
        String layer = feature.layer();

        if (currentLayer == null) {
          currentLayer = layer;
        } else if (!currentLayer.equals(layer)) {
          postProcessAndAddLayerFeatures(encoder, currentLayer, items);
          currentLayer = layer;
          items.clear();
        }

        items.add(feature);
      }
      postProcessAndAddLayerFeatures(encoder, currentLayer, items);
      return encoder;
    }

    private void postProcessAndAddLayerFeatures(VectorTile encoder, String layer,
      List<VectorTile.Feature> features) {
      try {
        List<VectorTile.Feature> postProcessed = profile
          .postProcessLayerFeatures(layer, tileCoord.z(), features);
        features = postProcessed == null ? features : postProcessed;
      } catch (Throwable e) {
        // failures in tile post-processing happen very late so err on the side of caution and
        // log failures, only throwing when it's a fatal error
        if (e instanceof GeometryException geoe) {
          geoe.log(stats, "postprocess_layer",
            "Caught error postprocessing features for " + layer + " layer on " + tileCoord);
        } else if (e instanceof Exception) {
          LOGGER.error("Caught error postprocessing features " + layer + " " + tileCoord, e);
        } else {
          LOGGER.error("Fatal error postprocessing features " + layer + " " + tileCoord, e);
        }
        if (e instanceof Error err) {
          throw err;
        }
      }
      encoder.addLayerFeatures(layer, features);
    }

    void add(SortableFeature entry) {
      numFeaturesProcessed.incrementAndGet();
      long sortKey = entry.sortKey();
      if (extractHasGroupFromSortKey(sortKey)) {
        byte thisLayer = extractLayerIdFromSortKey(sortKey);
        if (counts == null) {
          counts = new GHLongLongHashMap();
          layer = thisLayer;
        } else if (thisLayer != layer) {
          layer = thisLayer;
          counts.clear();
        }
        var groupInfo = peekAtGroupInfo(entry.value());
        long old = counts.getOrDefault(groupInfo.group(), 0);
        if (groupInfo.limit() > 0 && old >= groupInfo.limit()) {
          // discard if there are to many features in this group already
          return;
        }
        counts.put(groupInfo.group(), old + 1);
      }
      entries.add(entry);
    }

    @Override
    public String toString() {
      return "TileFeatures{" +
        "tile=" + tileCoord +
        ", num entries=" + entries.size() +
        '}';
    }
  }
}
