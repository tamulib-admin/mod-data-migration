package org.folio.rest.migration;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonStringEncoder;

import org.codehaus.plexus.util.StringUtils;
import org.folio.rest.jaxrs.model.feesfines.Accountdata;
import org.folio.rest.jaxrs.model.feesfines.actions.Feefineactiondata;
import org.folio.rest.jaxrs.model.inventory.Location;
import org.folio.rest.jaxrs.model.inventory.Locations;
import org.folio.rest.jaxrs.model.inventory.Materialtype;
import org.folio.rest.jaxrs.model.inventory.Materialtypes;
import org.folio.rest.jaxrs.model.users.Userdata;
import org.folio.rest.migration.config.model.Database;
import org.folio.rest.migration.model.FeeFineRecord;
import org.folio.rest.migration.model.request.feefine.FeeFineContext;
import org.folio.rest.migration.model.request.feefine.FeeFineDefaults;
import org.folio.rest.migration.model.request.feefine.FeeFineJob;
import org.folio.rest.migration.model.request.feefine.FeeFineMaps;
import org.folio.rest.migration.service.MigrationService;
import org.folio.rest.migration.utility.TimingUtility;
import org.folio.rest.model.ReferenceLink;
import org.postgresql.copy.PGCopyOutputStream;
import org.postgresql.core.BaseConnection;

public class FeeFineMigration extends AbstractMigration<FeeFineContext> {

  private static final String LOCATIONS_MAP = "LOCATIONS_MAP";
  private static final String MATERIAL_TYPES = "MATERIAL_TYPES";

  private static final String USER_ID = "USER_ID";

  private static final String PATRON_ID = "PATRON_ID";
  private static final String ITEM_ID = "ITEM_ID";
  private static final String ITEM_BARCODE = "ITEM_BARCODE";
  private static final String FINE_FEE_ID = "FINE_FEE_ID";
  private static final String AMOUNT = "AMOUNT";
  private static final String REMAINING = "REMAINING";
  private static final String FINE_FEE_TYPE = "FINE_FEE_TYPE";
  private static final String FINE_FEE_NOTE = "FINE_FEE_NOTE";
  private static final String CREATE_DATE = "CREATE_DATE";
  private static final String MFHD_ID = "MFHD_ID";
  private static final String DISPLAY_CALL_NO = "DISPLAY_CALL_NO";
  private static final String ITEM_ENUM = "ITEM_ENUM";
  private static final String CHRON = "CHRON";
  private static final String EFFECTIVE_LOCATION = "EFFECTIVE_LOCATION";
  private static final String FINE_LOCATION = "FINE_LOCATION";
  private static final String TITLE = "TITLE";
  private static final String BIB_ID = "BIB_ID";

  private static final String MTYPE_CODE = "MTYPE_CODE";

  private static final String LOCATION_ID = "LOCATION_ID";
  private static final String LOCATION_CODE = "LOCATION_CODE";

  private static final String USER_REFERENCE_ID = "userTypeId";
  private static final String USER_TO_EXTERNAL_REFERENCE_ID = "userToExternalTypeId";

  private static final String INSTANCE_REFERENCE_ID = "instanceTypeId";
  private static final String HOLDING_REFERENCE_ID = "holdingTypeId";
  private static final String ITEM_REFERENCE_ID = "itemTypeId";

  // (id,jsonb,creation_date,created_by)
  private static String ACCOUNT_COPY_SQL = "COPY %s_mod_feesfines.accounts (id,jsonb,creation_date,created_by) FROM STDIN WITH NULL AS 'null'";

  // (id,jsonb,creation_date,created_by)
  private static String FEEFINE_ACTION_COPY_SQL = "COPY %s_mod_feesfines.feefineactions (id,jsonb,creation_date,created_by) FROM STDIN WITH NULL AS 'null'";

  private FeeFineMigration(FeeFineContext context, String tenant) {
    super(context, tenant);
  }

  @Override
  public CompletableFuture<String> run(MigrationService migrationService) {
    log.info("running {} for tenant {}", this.getClass().getSimpleName(), tenant);

    String token = migrationService.okapiService.getToken(tenant);

    Locations locations = migrationService.okapiService.fetchLocations(tenant, token);
    Materialtypes materialTypes = migrationService.okapiService.fetchMaterialtypes(tenant, token);

    Database voyagerSettings = context.getExtraction().getDatabase();
    Database folioSettings = migrationService.okapiService.okapi.getModules().getDatabase();

    preActions(folioSettings, context.getPreActions());

    taskQueue = new PartitionTaskQueue<FeeFineContext>(context, new TaskCallback() {

      @Override
      public void complete() {
        postActions(folioSettings, context.getPostActions());
        migrationService.complete();
      }

    });

    Map<String, Object> countContext = new HashMap<>();
    countContext.put(SQL, context.getExtraction().getCountSql());

    int index = 0;

    for (FeeFineJob job : context.getJobs()) {

      countContext.put(SCHEMA, job.getSchema());

      Map<String, String> locationsMap = getLocationsMap(locations, job.getSchema());

      int count = getCount(voyagerSettings, countContext);

      log.info("{} count: {}", job.getSchema(), count);

      Userdata user = migrationService.okapiService.lookupUserByUsername(tenant, token, job.getUser());

      int partitions = job.getPartitions();
      int limit = (int) Math.ceil((double) count / (double) partitions);
      int offset = 0;
      for (int i = 0; i < partitions; i++) {
        Map<String, Object> partitionContext = new HashMap<String, Object>();
        partitionContext.put(SQL, context.getExtraction().getPageSql());
        partitionContext.put(SCHEMA, job.getSchema());
        partitionContext.put(OFFSET, offset);
        partitionContext.put(LIMIT, limit);
        partitionContext.put(INDEX, index);
        partitionContext.put(JOB, job);
        partitionContext.put(LOCATIONS_MAP, locationsMap);
        partitionContext.put(MATERIAL_TYPES, materialTypes);
        partitionContext.put(USER_ID, user.getId());
        log.info("submitting task schema {}, offset {}, limit {}", job.getSchema(), offset, limit);
        taskQueue.submit(new FeeFinePartitionTask(migrationService, partitionContext));
        offset += limit;
        index++;
      }
    }

    return CompletableFuture.completedFuture(IN_PROGRESS_RESPONSE_MESSAGE);
  }

  public static FeeFineMigration with(FeeFineContext context, String tenant) {
    return new FeeFineMigration(context, tenant);
  }

  public class FeeFinePartitionTask implements PartitionTask<FeeFineContext> {

    private final MigrationService migrationService;

    private final Map<String, Object> partitionContext;

    public FeeFinePartitionTask(MigrationService migrationService, Map<String, Object> partitionContext) {
      this.migrationService = migrationService;
      this.partitionContext = partitionContext;
    }

    public int getIndex() {
      return (int) partitionContext.get(INDEX);
    }

    public FeeFinePartitionTask execute(FeeFineContext context) {
      long startTime = System.nanoTime();

      FeeFineJob job = (FeeFineJob) partitionContext.get(JOB);

      FeeFineMaps maps = context.getMaps();
      FeeFineDefaults defaults = context.getDefaults();

      Materialtypes materialtypes = (Materialtypes) partitionContext.get(MATERIAL_TYPES);

      Map<String, String> locationsMap = (Map<String, String>) partitionContext.get(LOCATIONS_MAP);

      String userId = (String) partitionContext.get(USER_ID);

      String schema = job.getSchema();

      int index = this.getIndex();

      Database voyagerSettings = context.getExtraction().getDatabase();
      Database folioSettings = migrationService.okapiService.okapi.getModules().getDatabase();

      JsonStringEncoder jsonStringEncoder = new JsonStringEncoder();

      Map<String, Object> materialTypeContext = new HashMap<>();
      materialTypeContext.put(SQL, context.getExtraction().getMaterialTypeSql());
      materialTypeContext.put(SCHEMA, schema);

      String userRLTypeId = job.getReferences().get(USER_REFERENCE_ID);
      String userToExternalRLTypeId = job.getReferences().get(USER_TO_EXTERNAL_REFERENCE_ID);
      String instanceRLTypeId = job.getReferences().get(INSTANCE_REFERENCE_ID);
      String holdingRLTypeId = job.getReferences().get(HOLDING_REFERENCE_ID);
      String itemRLTypeId = job.getReferences().get(ITEM_REFERENCE_ID);

      ThreadConnections threadConnections = getThreadConnections(voyagerSettings, folioSettings);

      try (
        PrintWriter accountWriter = new PrintWriter(new PGCopyOutputStream(threadConnections.getAccountConnection(), String.format(ACCOUNT_COPY_SQL, tenant)), true);
        PrintWriter feefineActionWriter = new PrintWriter(new PGCopyOutputStream(threadConnections.getFeefineActionConnection(), String.format(FEEFINE_ACTION_COPY_SQL, tenant)), true);
        Statement pageStatement = threadConnections.getPageConnection().createStatement();
        Statement materialTypeStatement = threadConnections.getMaterialTypeConnection().createStatement();
        ResultSet pageResultSet = getResultSet(pageStatement, partitionContext);
      ) {
        while (pageResultSet.next()) {
          String patronId = pageResultSet.getString(PATRON_ID);
          String itemId = pageResultSet.getString(ITEM_ID);
          String itemBarcode = pageResultSet.getString(ITEM_BARCODE);
          String finefeeId = pageResultSet.getString(FINE_FEE_ID);
          String amount = pageResultSet.getString(AMOUNT);
          String remaining = pageResultSet.getString(REMAINING);
          String finefeeType = pageResultSet.getString(FINE_FEE_TYPE);
          String finefeeNote = pageResultSet.getString(FINE_FEE_NOTE);
          String createDate = pageResultSet.getString(CREATE_DATE);
          String mfhdId = pageResultSet.getString(MFHD_ID);
          String displayCallNo = pageResultSet.getString(DISPLAY_CALL_NO);
          String itemEnum = pageResultSet.getString(ITEM_ENUM);
          String chron = pageResultSet.getString(CHRON);
          String effectiveLocation = pageResultSet.getString(EFFECTIVE_LOCATION);
          String fineLocation = pageResultSet.getString(FINE_LOCATION);
          String title = pageResultSet.getString(TITLE);
          String bibId = pageResultSet.getString(BIB_ID);

          FeeFineRecord feefineRecord = new FeeFineRecord(
            patronId,
            itemId,
            itemBarcode,
            finefeeId,
            amount,
            remaining,
            finefeeType,
            finefeeNote,
            createDate,
            mfhdId,
            displayCallNo,
            itemEnum,
            chron,
            effectiveLocation,
            fineLocation,
            title,
            bibId
          );

          if (locationsMap.containsKey(effectiveLocation)) {
            feefineRecord.setLocation(locationsMap.get(effectiveLocation));
          }

          Optional<ReferenceLink> userRL = migrationService.referenceLinkRepo.findByTypeIdAndExternalReference(userRLTypeId, patronId);

          if (!userRL.isPresent()) {
            log.error("{} no user id found for patron id {}", schema, patronId);
            continue;
          }

          Optional<ReferenceLink> userToExternalRL = migrationService.referenceLinkRepo.findByTypeIdAndExternalReference(userToExternalRLTypeId, userRL.get().getId());

          if (!userToExternalRL.isPresent()) {
            log.error("{} no user to external id found for patron id {}", schema, patronId);
            continue;
          }

          Optional<ReferenceLink> userExternalRL = migrationService.referenceLinkRepo.findById(userToExternalRL.get().getFolioReference());

          if (!userExternalRL.isPresent()) {
            log.error("{} no user external id found for patron id {}", schema, patronId);
            continue;
          }

          String externalSystemId = userExternalRL.get().getExternalReference();

          List<ReferenceLink> userReferenceLinks = migrationService.referenceLinkRepo.findAllByExternalReference(externalSystemId);

          if (userReferenceLinks.isEmpty()) {
            log.error("{} no user id found for patron id {}", schema, patronId);
            continue;
          }

          String referenceId = userReferenceLinks.get(0).getFolioReference().toString();

          feefineRecord.setUserId(referenceId);

          if (StringUtils.isNotEmpty(itemId)) {
            materialTypeContext.put(ITEM_ID, itemId);
            feefineRecord.setMaterialTypeId(getMaterialTypeId(materialTypeStatement, materialTypeContext, materialtypes));
            feefineRecord.setInstanceRL(migrationService.referenceLinkRepo.findByTypeIdAndExternalReference(instanceRLTypeId, bibId));
            feefineRecord.setHoldingRL(migrationService.referenceLinkRepo.findByTypeIdAndExternalReference(holdingRLTypeId, mfhdId));
            feefineRecord.setItemRL(migrationService.referenceLinkRepo.findByTypeIdAndExternalReference(itemRLTypeId, itemId));
          }

          Date createdDate = new Date();
          feefineRecord.setCreatedByUserId(userId);
          feefineRecord.setCreatedDate(createdDate);

          Accountdata account = feefineRecord.toAccount(maps, defaults, schema);

          Feefineactiondata feefineaction = feefineRecord.toFeefineaction(account, maps, defaults);

          String createdAt = DATE_TIME_FOMATTER.format(createdDate.toInstant().atOffset(ZoneOffset.UTC));;
          String createdByUserId = userId;

          try {
            String aUtf8Json = new String(jsonStringEncoder.quoteAsUTF8(migrationService.objectMapper.writeValueAsString(account)));
            String ffaUtf8Json = new String(jsonStringEncoder.quoteAsUTF8(migrationService.objectMapper.writeValueAsString(feefineaction)));

            // (id,jsonb,creation_date,created_by)
            accountWriter.println(String.join("\t", account.getId(), aUtf8Json, createdAt, createdByUserId));

            // (id,jsonb,creation_date,created_by)
            feefineActionWriter.println(String.join("\t", feefineaction.getId(), ffaUtf8Json, createdAt, createdByUserId));

          } catch (JsonProcessingException e) {
            log.error("{} fine fee id {} error serializing fee fine or action", schema, finefeeId);
            log.debug(e.getMessage());
          }

        }

      } catch (SQLException e) {
        e.printStackTrace();
      } finally {
        threadConnections.closeAll();
      }
      log.info("{} {} finished in {} milliseconds", schema, index, TimingUtility.getDeltaInMilliseconds(startTime));

      return this;
    }

    @Override
    public boolean equals(Object obj) {
      return Objects.nonNull(obj) && ((FeeFinePartitionTask) obj).getIndex() == this.getIndex();
    }

  }

  private Optional<String> getMaterialTypeId(Statement statement, Map<String, Object> context, Materialtypes materialtypes) {
    Optional<String> materialTypeId = Optional.empty();
      try (ResultSet resultSet = getResultSet(statement, context)) {
        while (resultSet.next()) {
          String materialTypeCode = resultSet.getString(MTYPE_CODE);
          Optional<Materialtype> potentialMaterialType = materialtypes.getMtypes().stream().filter(mt -> mt.getSource().equals(materialTypeCode)).findFirst();
          if (potentialMaterialType.isPresent()) {
            materialTypeId = Optional.of(potentialMaterialType.get().getId());
          }
          break;
        }
      } catch (SQLException e) {
        e.printStackTrace();
      } finally {

      }
    return materialTypeId;
  }

  private Map<String, String> getLocationsMap(Locations locations, String schema) {
    Map<String, String> idToDisplayName = new HashMap<>();
    Map<String, Object> locationContext = new HashMap<>();
    locationContext.put(SQL, context.getExtraction().getLocationSql());
    locationContext.put(SCHEMA, schema);
    Database voyagerSettings = context.getExtraction().getDatabase();
    Map<String, String> locConv = context.getMaps().getLocation().get(schema);
    try (
      Connection voyagerConnection = getConnection(voyagerSettings);
      Statement st = voyagerConnection.createStatement();
      ResultSet rs = getResultSet(st, locationContext);
    ) {
      while (rs.next()) {
        String id = rs.getString(LOCATION_ID);
        if (Objects.nonNull(id)) {
          String code = locConv.containsKey(id) ? locConv.get(id) : rs.getString(LOCATION_CODE);
          Optional<Location> location = locations.getLocations().stream().filter(loc -> loc.getCode().equals(code)).findFirst();
          if (location.isPresent()) {
            idToDisplayName.put(id, location.get().getDiscoveryDisplayName());
          }
        }
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return idToDisplayName;
  }

  private ThreadConnections getThreadConnections(Database voyagerSettings, Database folioSettings) {
    ThreadConnections threadConnections = new ThreadConnections();
    threadConnections.setPageConnection(getConnection(voyagerSettings));
    threadConnections.setMaterialTypeConnection(getConnection(voyagerSettings));
    try {
      threadConnections.setAccountConnection(getConnection(folioSettings).unwrap(BaseConnection.class));
    } catch (SQLException e) {
      log.error(e.getMessage());
      throw new RuntimeException(e);
    }
    try {
      threadConnections.setFeefineActionConnection(getConnection(folioSettings).unwrap(BaseConnection.class));
    } catch (SQLException e) {
      log.error(e.getMessage());
      throw new RuntimeException(e);
    }
    return threadConnections;
  }

  private class ThreadConnections {

    private Connection pageConnection;
    private Connection materialTypeConnection;

    private BaseConnection accountConnection;
    private BaseConnection feefineActionConnection;

    public ThreadConnections() {

    }

    public Connection getPageConnection() {
      return pageConnection;
    }

    public void setPageConnection(Connection pageConnection) {
      this.pageConnection = pageConnection;
    }

    public Connection getMaterialTypeConnection() {
      return materialTypeConnection;
    }

    public void setMaterialTypeConnection(Connection materialTypeConnection) {
      this.materialTypeConnection = materialTypeConnection;
    }

    public BaseConnection getAccountConnection() {
      return accountConnection;
    }

    public void setAccountConnection(BaseConnection accountConnection) {
      this.accountConnection = accountConnection;
    }

    public BaseConnection getFeefineActionConnection() {
      return feefineActionConnection;
    }

    public void setFeefineActionConnection(BaseConnection feefineActionConnection) {
      this.feefineActionConnection = feefineActionConnection;
    }

    public void closeAll() {
      try {
        pageConnection.close();
        materialTypeConnection.close();
        accountConnection.close();
        feefineActionConnection.close();
      } catch (SQLException e) {
        log.error(e.getMessage());
        throw new RuntimeException(e);
      }
    }

  }

}
