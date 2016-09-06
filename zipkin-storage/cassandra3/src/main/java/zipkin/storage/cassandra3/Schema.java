/**
 * Copyright 2015-2016 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.storage.cassandra3;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.ProtocolVersion;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TypeCodec;
import com.datastax.driver.core.exceptions.InvalidTypeException;
import com.datastax.driver.mapping.annotations.UDT;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin.Annotation;
import zipkin.BinaryAnnotation;
import zipkin.Endpoint;

import static zipkin.storage.cassandra3.CassandraStorage.DEFAULT_KEYSPACE;

final class Schema {
  private static final Logger LOG = LoggerFactory.getLogger(Schema.class);

  private static final String SCHEMA = "/cassandra3-schema.cql";

  private Schema() {
  }

  static Metadata readMetadata(Session session) {
    KeyspaceMetadata keyspaceMetadata = getKeyspaceMetadata(session);

    Map<String, String> replication = keyspaceMetadata.getReplication();
    if ("SimpleStrategy".equals(replication.get("class")) && "1".equals(
        replication.get("replication_factor"))) {
      LOG.warn("running with RF=1, this is not suitable for production. Optimal is 3+");
    }
    String compactionClass =
        keyspaceMetadata.getTable("traces").getOptions().getCompaction().get("class");

    return new Metadata(compactionClass);
  }

  static final class Metadata {
    final String compactionClass;

    Metadata(String compactionClass) {
      this.compactionClass = compactionClass;
    }
  }

  static KeyspaceMetadata getKeyspaceMetadata(Session session) {
    String keyspace = session.getLoggedKeyspace();
    Cluster cluster = session.getCluster();
    KeyspaceMetadata keyspaceMetadata = cluster.getMetadata().getKeyspace(keyspace);

    if (keyspaceMetadata == null) {
      throw new IllegalStateException(String.format(
          "Cannot read keyspace metadata for give keyspace: %s and cluster: %s",
          keyspace, cluster.getClusterName()));
    }
    return keyspaceMetadata;
  }

  static void ensureExists(String keyspace, Session session) {
    KeyspaceMetadata keyspaceMetadata = session.getCluster().getMetadata().getKeyspace(keyspace);
    if (keyspaceMetadata == null || keyspaceMetadata.getTable("traces") == null) {
      LOG.info("Installing schema {}", SCHEMA);
      applyCqlFile(keyspace, session, SCHEMA);
      // refresh metadata since we've installed the schema
      keyspaceMetadata = session.getCluster().getMetadata().getKeyspace(keyspace);
    }
  }

  static void applyCqlFile(String keyspace, Session session, String resource) {
    try (Reader reader = new InputStreamReader(Schema.class.getResourceAsStream(resource))) {
      for (String cmd : CharStreams.toString(reader).split(";")) {
        cmd = cmd.trim().replace(" zipkin3", " " + keyspace);
        if (!cmd.isEmpty()) {
          session.execute(cmd);
        }
      }
    } catch (IOException ex) {
      LOG.error(ex.getMessage(), ex);
    }
  }

  @UDT(keyspace = DEFAULT_KEYSPACE + "_udts", name = "endpoint")
  static final class EndpointUDT {

    private String service_name;
    private InetAddress ipv4;
    private InetAddress ipv6;
    private short port;

    EndpointUDT() {
      this.service_name = null;
      this.ipv4 = null;
      this.ipv6 = null;
      this.port = 0;
    }

    EndpointUDT(Endpoint endpoint) {
      this.service_name = endpoint.serviceName;
      this.ipv4 = ipv4(endpoint.ipv4);
      if (null != endpoint.ipv6 && 16 == endpoint.ipv6.length) {
        this.ipv6 = ipv6(endpoint.ipv6);
      }
      this.port = endpoint.port;
    }

    private static Inet4Address ipv4(int ipv4) {
      try {
        return (Inet4Address) Inet4Address.getByAddress(BigInteger.valueOf(ipv4).toByteArray());
      } catch (UnknownHostException ex) {
        return null;
      }
    }

    private static Inet6Address ipv6(byte[] ipv6) {
      try {
        return (Inet6Address) Inet6Address.getByAddress(ipv6);
      } catch (UnknownHostException ex) {
        return null;
      }
    }

    public String getService_name() {
      return service_name;
    }

    public InetAddress getIpv4() {
      return ipv4;
    }

    public InetAddress getIpv6() {
      return ipv6;
    }

    public short getPort() {
      return port;
    }

    public void setService_name(String service_name) {
      this.service_name = service_name;
    }

    public void setIpv4(InetAddress ipv4) {
      this.ipv4 = (Inet4Address) ipv4;
    }

    public void setIpv6(InetAddress ipv6) {
      this.ipv6 = (Inet6Address) ipv6;
    }

    public void setPort(short port) {
      this.port = port;
    }

    private Endpoint toEndpoint() {
      Endpoint.Builder builder = Endpoint.builder()
          .serviceName(service_name)
          .ipv4(new BigInteger(ipv4.getAddress()).intValue())
          .port(port);

      if (null != ipv6) {
        builder = builder.ipv6(ipv6.getAddress());
      }
      return builder.build();
    }
  }

  @UDT(keyspace = DEFAULT_KEYSPACE + "_udts", name = "annotation")
  static final class AnnotationUDT {

    private long ts;
    private String v;
    private EndpointUDT ep;

    AnnotationUDT() {
      this.ts = 0;
      this.v = null;
      this.ep = null;
    }

    AnnotationUDT(Annotation annotation) {
      this.ts = annotation.timestamp;
      this.v = annotation.value;
      this.ep = null != annotation.endpoint ? new EndpointUDT(annotation.endpoint) : null;
    }

    public long getTs() {
      return ts;
    }

    public String getV() {
      return v;
    }

    public EndpointUDT getEp() {
      return ep;
    }

    public void setTs(long ts) {
      this.ts = ts;
    }

    public void setV(String v) {
      this.v = v;
    }

    public void setEp(EndpointUDT ep) {
      this.ep = ep;
    }

    Annotation toAnnotation() {
      Annotation.Builder builder = Annotation.builder().timestamp(ts).value(v);
      if (null != ep) {
        builder = builder.endpoint(ep.toEndpoint());
      }
      return builder.build();
    }
  }

  @UDT(keyspace = DEFAULT_KEYSPACE + "_udts", name = "binary_annotation")
  static final class BinaryAnnotationUDT {

    private String k;
    private ByteBuffer v;
    private String t;
    private EndpointUDT ep;

    BinaryAnnotationUDT() {
      this.k = null;
      this.v = null;
      this.t = null;
      this.ep = null;
    }

    BinaryAnnotationUDT(BinaryAnnotation annotation) {
      this.k = annotation.key;
      this.v = null != annotation.value ? ByteBuffer.wrap(annotation.value) : null;
      this.t = annotation.type.name();
      this.ep = new EndpointUDT(annotation.endpoint);
    }

    public String getK() {
      return k;
    }

    public ByteBuffer getV() {
      return v.duplicate();
    }

    public String getT() {
      return t;
    }

    public EndpointUDT getEp() {
      return ep;
    }

    public void setK(String k) {
      this.k = k;
    }

    public void setV(ByteBuffer v) {
      byte[] bytes = new byte[v.remaining()];
      v.duplicate().get(bytes);
      this.v = ByteBuffer.wrap(bytes);
    }

    public void setT(String t) {
      this.t = t;
    }

    public void setEp(EndpointUDT ep) {
      this.ep = ep;
    }

    BinaryAnnotation toBinaryAnnotation() {
      BinaryAnnotation.Type type = BinaryAnnotation.Type.valueOf(t);
      BinaryAnnotation.Builder builder = BinaryAnnotation.builder().key(k).type(type);
      if (null != v) {
        builder = builder.value(getV().array());
      }
      if (null != ep) {
        builder = builder.endpoint(ep.toEndpoint());
      }
      return builder.build();
    }
  }

  static final class TypeCodecImpl<T> extends TypeCodec<T> {

    private final TypeCodec<T> codec;

    public TypeCodecImpl(DataType cqlType, Class<T> javaClass, TypeCodec<T> codec) {
      super(cqlType, javaClass);
      this.codec = codec;
    }

    @Override
    public ByteBuffer serialize(T t, ProtocolVersion pv) throws InvalidTypeException {
      return codec.serialize(t, pv);
    }

    @Override
    public T deserialize(ByteBuffer bb, ProtocolVersion pv) throws InvalidTypeException {
      return codec.deserialize(bb, pv);
    }

    @Override
    public T parse(String string) throws InvalidTypeException {
      return codec.parse(string);
    }

    @Override
    public String format(T t) throws InvalidTypeException {
      return codec.format(t);
    }
  }
}
