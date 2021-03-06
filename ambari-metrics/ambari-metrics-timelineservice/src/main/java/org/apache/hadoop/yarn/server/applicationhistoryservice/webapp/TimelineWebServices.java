/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.applicationhistoryservice.webapp;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntities;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEvents;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse;
import org.apache.hadoop.metrics2.sink.timeline.Precision;
import org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricStore;
import org.apache.hadoop.yarn.server.applicationhistoryservice.timeline.EntityIdentifier;
import org.apache.hadoop.yarn.server.applicationhistoryservice.timeline.GenericObjectMapper;
import org.apache.hadoop.yarn.server.applicationhistoryservice.timeline.NameValuePair;
import org.apache.hadoop.yarn.server.applicationhistoryservice.timeline.TimelineReader.Field;
import org.apache.hadoop.yarn.server.applicationhistoryservice.timeline.TimelineStore;
import org.apache.hadoop.yarn.util.timeline.TimelineUtils;
import org.apache.hadoop.yarn.webapp.BadRequestException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.apache.hadoop.yarn.util.StringHelper.CSV_JOINER;

@Singleton
@Path("/ws/v1/timeline")
//TODO: support XML serialization/deserialization
public class TimelineWebServices {

  private static final Log LOG = LogFactory.getLog(TimelineWebServices.class);

  private TimelineStore store;
  private TimelineMetricStore timelineMetricStore;

  @Inject
  public TimelineWebServices(TimelineStore store,
                             TimelineMetricStore timelineMetricStore) {
    this.store = store;
    this.timelineMetricStore = timelineMetricStore;
  }

  @XmlRootElement(name = "about")
  @XmlAccessorType(XmlAccessType.NONE)
  @Public
  @Unstable
  public static class AboutInfo {

    private String about;

    public AboutInfo() {

    }

    public AboutInfo(String about) {
      this.about = about;
    }

    @XmlElement(name = "About")
    public String getAbout() {
      return about;
    }

    public void setAbout(String about) {
      this.about = about;
    }

  }

  /**
   * Return the description of the timeline web services.
   */
  @GET
  @Produces({ MediaType.APPLICATION_JSON /* , MediaType.APPLICATION_XML */})
  public AboutInfo about(
      @Context HttpServletRequest req,
      @Context HttpServletResponse res) {
    init(res);
    return new AboutInfo("Timeline API");
  }

  /**
   * Return a list of entities that match the given parameters.
   */
  @GET
  @Path("/{entityType}")
  @Produces({ MediaType.APPLICATION_JSON /* , MediaType.APPLICATION_XML */})
  public TimelineEntities getEntities(
      @Context HttpServletRequest req,
      @Context HttpServletResponse res,
      @PathParam("entityType") String entityType,
      @QueryParam("primaryFilter") String primaryFilter,
      @QueryParam("secondaryFilter") String secondaryFilter,
      @QueryParam("windowStart") String windowStart,
      @QueryParam("windowEnd") String windowEnd,
      @QueryParam("fromId") String fromId,
      @QueryParam("fromTs") String fromTs,
      @QueryParam("limit") String limit,
      @QueryParam("fields") String fields) {
    init(res);
    TimelineEntities entities = null;
    try {
      entities = store.getEntities(
          parseStr(entityType),
          parseLongStr(limit),
          parseLongStr(windowStart),
          parseLongStr(windowEnd),
          parseStr(fromId),
          parseLongStr(fromTs),
          parsePairStr(primaryFilter, ":"),
          parsePairsStr(secondaryFilter, ",", ":"),
          parseFieldsStr(fields, ","));
    } catch (NumberFormatException e) {
      throw new BadRequestException(
          "windowStart, windowEnd or limit is not a numeric value.");
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("requested invalid field.");
    } catch (IOException e) {
      LOG.error("Error getting entities", e);
      throw new WebApplicationException(e,
          Response.Status.INTERNAL_SERVER_ERROR);
    }
    if (entities == null) {
      return new TimelineEntities();
    }
    return entities;
  }

  /**
   * Return a single entity of the given entity type and Id.
   */
  @GET
  @Path("/{entityType}/{entityId}")
  @Produces({ MediaType.APPLICATION_JSON /* , MediaType.APPLICATION_XML */})
  public TimelineEntity getEntity(
      @Context HttpServletRequest req,
      @Context HttpServletResponse res,
      @PathParam("entityType") String entityType,
      @PathParam("entityId") String entityId,
      @QueryParam("fields") String fields) {
    init(res);
    TimelineEntity entity = null;
    try {
      entity =
          store.getEntity(parseStr(entityId), parseStr(entityType),
            parseFieldsStr(fields, ","));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(
          "requested invalid field.");
    } catch (IOException e) {
      LOG.error("Error getting entity", e);
      throw new WebApplicationException(e,
          Response.Status.INTERNAL_SERVER_ERROR);
    }
    if (entity == null) {
      throw new WebApplicationException(Response.Status.NOT_FOUND);
    }
    return entity;
  }

  /**
   * Return the events that match the given parameters.
   */
  @GET
  @Path("/{entityType}/events")
  @Produces({ MediaType.APPLICATION_JSON /* , MediaType.APPLICATION_XML */})
  public TimelineEvents getEvents(
      @Context HttpServletRequest req,
      @Context HttpServletResponse res,
      @PathParam("entityType") String entityType,
      @QueryParam("entityId") String entityId,
      @QueryParam("eventType") String eventType,
      @QueryParam("windowStart") String windowStart,
      @QueryParam("windowEnd") String windowEnd,
      @QueryParam("limit") String limit) {
    init(res);
    TimelineEvents events = null;
    try {
      events = store.getEntityTimelines(
        parseStr(entityType),
        parseArrayStr(entityId, ","),
        parseLongStr(limit),
        parseLongStr(windowStart),
        parseLongStr(windowEnd),
        parseArrayStr(eventType, ","));
    } catch (NumberFormatException e) {
      throw new BadRequestException(
          "windowStart, windowEnd or limit is not a numeric value.");
    } catch (IOException e) {
      LOG.error("Error getting entity timelines", e);
      throw new WebApplicationException(e,
          Response.Status.INTERNAL_SERVER_ERROR);
    }
    if (events == null) {
      return new TimelineEvents();
    }
    return events;
  }

  /**
   * Store the given metrics into the timeline store, and return errors that
   * happened during storing.
   */
  @Path("/metrics")
  @POST
  @Consumes({ MediaType.APPLICATION_JSON /* , MediaType.APPLICATION_XML */})
  public TimelinePutResponse postMetrics(
    @Context HttpServletRequest req,
    @Context HttpServletResponse res,
    TimelineMetrics metrics) {

    init(res);
    if (metrics == null) {
      return new TimelinePutResponse();
    }

    try {

      // TODO: Check ACLs for MetricEntity using the TimelineACLManager.
      // TODO: Save owner of the MetricEntity.

      if (LOG.isDebugEnabled()) {
        LOG.debug("Storing metrics: " +
          TimelineUtils.dumpTimelineRecordtoJSON(metrics, true));
      }

      return timelineMetricStore.putMetrics(metrics);

    } catch (Exception e) {
      LOG.error("Error saving metrics.", e);
      throw new WebApplicationException(e, Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  /**
   * Query for a particular metric satisfying the filter criteria.
   * @return {@link TimelineMetric}
   */
  @GET
  @Path("/metrics/{metricName}")
  @Produces({ MediaType.APPLICATION_JSON /* , MediaType.APPLICATION_XML */})
  public TimelineMetric getTimelineMetric(
    @Context HttpServletRequest req,
    @Context HttpServletResponse res,
    @PathParam("metricName") String metricName,
    @QueryParam("appId") String appId,
    @QueryParam("instanceId") String instanceId,
    @QueryParam("hostname") String hostname,
    @QueryParam("startTime") String startTime,
    @QueryParam("endTime") String endTime,
    @QueryParam("precision") String precision,
    @QueryParam("limit") String limit
  ) {
    init(res);
    try {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Request for metrics => metricName: " + metricName + ", " +
          "appId: " + appId + ", instanceId: " + instanceId + ", " +
          "hostname: " + hostname + ", startTime: " + startTime + ", " +
          "endTime: " + endTime);
      }

      return timelineMetricStore.getTimelineMetric(metricName,
        parseListStr(hostname, ","), appId, instanceId, parseLongStr(startTime),
        parseLongStr(endTime), Precision.getPrecision(precision),
        parseIntStr(limit));
    } catch (NumberFormatException ne) {
      throw new BadRequestException("startTime, endTime and limit should be " +
        "numeric values");
    } catch (Precision.PrecisionFormatException pfe) {
      throw new BadRequestException("precision should be seconds, minutes " +
        "or hours");
    } catch (IllegalArgumentException iae) {
      throw new BadRequestException(iae.getMessage());
    } catch (SQLException | IOException sql) {
      throw new WebApplicationException(sql,
        Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  /**
   * Query for a set of different metrics satisfying the filter criteria.
   * All query params are optional. The default limit will apply if none
   * specified.
   *
   * @param metricNames Comma separated list of metrics to retrieve.
   * @param appId Application Id for the requested metrics.
   * @param instanceId Application instance id.
   * @param hostname Hostname where the metrics originated.
   * @param startTime Start time for the metric records retrieved.
   * @param precision Precision [ seconds, minutes, hours ]
   * @param limit limit on total number of {@link TimelineMetric} records
   *              retrieved.
   * @return {@link TimelineMetrics}
   */
  @GET
  @Path("/metrics")
  @Produces({ MediaType.APPLICATION_JSON /* , MediaType.APPLICATION_XML */})
  public TimelineMetrics getTimelineMetrics(
    @Context HttpServletRequest req,
    @Context HttpServletResponse res,
    @QueryParam("metricNames") String metricNames,
    @QueryParam("appId") String appId,
    @QueryParam("instanceId") String instanceId,
    @QueryParam("hostname") String hostname,
    @QueryParam("startTime") String startTime,
    @QueryParam("endTime") String endTime,
    @QueryParam("precision") String precision,
    @QueryParam("limit") String limit,
    @QueryParam("grouped") String grouped
  ) {
    init(res);
    try {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Request for metrics => metricNames: " + metricNames + ", " +
          "appId: " + appId + ", instanceId: " + instanceId + ", " +
          "hostname: " + hostname + ", startTime: " + startTime + ", " +
          "endTime: " + endTime + ", " +
          "precision: " + precision);
      }

      return timelineMetricStore.getTimelineMetrics(
        parseListStr(metricNames, ","), parseListStr(hostname, ","), appId, instanceId,
        parseLongStr(startTime), parseLongStr(endTime),
        Precision.getPrecision(precision), parseIntStr(limit),
        parseBoolean(grouped));

    } catch (NumberFormatException ne) {
      throw new BadRequestException("startTime and limit should be numeric " +
        "values");
    } catch (Precision.PrecisionFormatException pfe) {
      throw new BadRequestException("precision should be seconds, minutes " +
        "or hours");
    } catch (IllegalArgumentException iae) {
      throw new BadRequestException(iae.getMessage());
    } catch (SQLException sql) {
      throw new WebApplicationException(sql,
        Response.Status.INTERNAL_SERVER_ERROR);
    } catch (IOException io) {
      throw new WebApplicationException(io,
        Response.Status.INTERNAL_SERVER_ERROR);
    }
  }


  /**
   * Store the given entities into the timeline store, and return the errors
   * that happen during storing.
   */
  @POST
  @Consumes({ MediaType.APPLICATION_JSON /* , MediaType.APPLICATION_XML */})
  public TimelinePutResponse postEntities(
      @Context HttpServletRequest req,
      @Context HttpServletResponse res,
      TimelineEntities entities) {
    init(res);
    if (entities == null) {
      return new TimelinePutResponse();
    }
    try {
      List<EntityIdentifier> entityIDs = new ArrayList<EntityIdentifier>();
      for (TimelineEntity entity : entities.getEntities()) {
        EntityIdentifier entityID =
            new EntityIdentifier(entity.getEntityId(), entity.getEntityType());
        entityIDs.add(entityID);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Storing the entity " + entityID + ", JSON-style content: "
              + TimelineUtils.dumpTimelineRecordtoJSON(entity));
        }
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Storing entities: " + CSV_JOINER.join(entityIDs));
      }
      return store.put(entities);
    } catch (IOException e) {
      LOG.error("Error putting entities", e);
      throw new WebApplicationException(e,
          Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  private void init(HttpServletResponse response) {
    response.setContentType(null);
  }

  private static SortedSet<String> parseArrayStr(String str, String delimiter) {
    if (str == null) {
      return null;
    }
    SortedSet<String> strSet = new TreeSet<String>();
    String[] strs = str.split(delimiter);
    for (String aStr : strs) {
      strSet.add(aStr.trim());
    }
    return strSet;
  }

  private static NameValuePair parsePairStr(String str, String delimiter) {
    if (str == null) {
      return null;
    }
    String[] strs = str.split(delimiter, 2);
    try {
      return new NameValuePair(strs[0].trim(),
          GenericObjectMapper.OBJECT_READER.readValue(strs[1].trim()));
    } catch (Exception e) {
      // didn't work as an Object, keep it as a String
      return new NameValuePair(strs[0].trim(), strs[1].trim());
    }
  }

  private static Collection<NameValuePair> parsePairsStr(
      String str, String aDelimiter, String pDelimiter) {
    if (str == null) {
      return null;
    }
    String[] strs = str.split(aDelimiter);
    Set<NameValuePair> pairs = new HashSet<NameValuePair>();
    for (String aStr : strs) {
      pairs.add(parsePairStr(aStr, pDelimiter));
    }
    return pairs;
  }

  private static EnumSet<Field> parseFieldsStr(String str, String delimiter) {
    if (str == null) {
      return null;
    }
    String[] strs = str.split(delimiter);
    List<Field> fieldList = new ArrayList<Field>();
    for (String s : strs) {
      s = s.trim().toUpperCase();
      if (s.equals("EVENTS")) {
        fieldList.add(Field.EVENTS);
      } else if (s.equals("LASTEVENTONLY")) {
        fieldList.add(Field.LAST_EVENT_ONLY);
      } else if (s.equals("RELATEDENTITIES")) {
        fieldList.add(Field.RELATED_ENTITIES);
      } else if (s.equals("PRIMARYFILTERS")) {
        fieldList.add(Field.PRIMARY_FILTERS);
      } else if (s.equals("OTHERINFO")) {
        fieldList.add(Field.OTHER_INFO);
      } else {
        throw new IllegalArgumentException("Requested nonexistent field " + s);
      }
    }
    if (fieldList.size() == 0) {
      return null;
    }
    Field f1 = fieldList.remove(fieldList.size() - 1);
    if (fieldList.size() == 0) {
      return EnumSet.of(f1);
    } else {
      return EnumSet.of(f1, fieldList.toArray(new Field[fieldList.size()]));
    }
  }

  private static Long parseLongStr(String str) {
    return str == null ? null : Long.parseLong(str.trim());
  }

  private static Integer parseIntStr(String str) {
    return str == null ? null : Integer.parseInt(str.trim());
  }

  private static boolean parseBoolean(String booleanStr) {
    return booleanStr == null || Boolean.parseBoolean(booleanStr);
  }

  /**
   * Parses delimited string to list of strings. It skips strings that are
   * effectively empty (i.e. only whitespace).
   *
   */
  private static List<String> parseListStr(String str, String delimiter) {
    if (str == null || str.trim().isEmpty()){
      return null;
    }

    String[] split = str.trim().split(delimiter);
    List<String> list = new ArrayList<String>(split.length);
    for (String s : split) {
      if (!s.trim().isEmpty()){
        list.add(s);
      }
    }

    return list;
  }

  private static String parseStr(String str) {
    return str == null ? null : str.trim();
  }
}
