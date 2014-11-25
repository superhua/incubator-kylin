package com.kylinolap.job;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kylinolap.common.KylinConfig;
import com.kylinolap.common.persistence.ResourceTool;
import com.kylinolap.common.util.AbstractKylinTestCase;
import com.kylinolap.cube.CubeInstance;
import com.kylinolap.cube.CubeManager;
import com.kylinolap.cube.dataGen.FactTableGenerator;
import com.kylinolap.job.engine.JobEngineConfig;
import com.kylinolap.job.hadoop.hive.SqlHiveDataTypeMapping;
import com.kylinolap.job.tools.LZOSupportnessChecker;
import com.kylinolap.metadata.MetadataManager;
import com.kylinolap.metadata.model.schema.ColumnDesc;
import com.kylinolap.metadata.model.schema.TableDesc;

public class DeployUtil {
    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(DeployUtil.class);

    public static void initCliWorkDir() throws IOException {
        execCliCommand("rm -rf " + getHadoopCliWorkingDir());
        execCliCommand("mkdir -p " + config().getKylinJobLogDir());
    }

    public static void deployMetadata() throws IOException {
        // install metadata to hbase
        ResourceTool.reset(config());
        ResourceTool.copy(KylinConfig.createInstanceFromUri(AbstractKylinTestCase.LOCALMETA_TEST_DATA), config());

        // update cube desc signature.
        for (CubeInstance cube : CubeManager.getInstance(config()).listAllCubes()) {
            cube.getDescriptor().setSignature(cube.getDescriptor().calculateSignature());
            CubeManager.getInstance(config()).updateCube(cube);
        }
    }

    public static void overrideJobJarLocations() {
        String version;
        try {
            MavenXpp3Reader pomReader = new MavenXpp3Reader();
            Model model = pomReader.read(new FileReader("../pom.xml"));
            version = model.getVersion();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        String jobTargetDir = "../job/target";
        File jobJar = new File(jobTargetDir, "kylin-job-" + version + "-job.jar");
        File coprocessorJar = new File(jobTargetDir, "kylin-storage-" + version + "-coprocessor.jar");

        config().overrideKylinJobJarPath(jobJar.getAbsolutePath());
        config().overrideCoprocessorLocalJar(coprocessorJar.getAbsolutePath());
    }
    
    public static void overrideJobConf(String confDir) throws IOException {
        boolean enableLzo = LZOSupportnessChecker.getSupportness();
        overrideJobConf(confDir, enableLzo);
    }

    public static void overrideJobConf(String confDir, boolean enableLzo) throws IOException {
        File src = new File(confDir, JobEngineConfig.HADOOP_JOB_CONF_FILENAME + (enableLzo ? ".lzo_enabled" : ".lzo_disabled") + ".xml");
        File dst = new File(confDir, JobEngineConfig.HADOOP_JOB_CONF_FILENAME + ".xml");
        FileUtils.copyFile(src, dst);
    }

    private static void execCliCommand(String cmd) throws IOException {
        config().getCliCommandExecutor().execute(cmd);
    }

    private static String getHadoopCliWorkingDir() {
        return config().getCliWorkingDir();
    }

    private static KylinConfig config() {
        return KylinConfig.getInstanceFromEnv();
    }

    // ============================================================================

    static final String TABLE_CAL_DT = "test_cal_dt";
    static final String TABLE_CATEGORY_GROUPINGS = "test_category_groupings";
    static final String TABLE_KYLIN_FACT = "test_kylin_fact";
    static final String TABLE_SELLER_TYPE_DIM = "test_seller_type_dim";
    static final String TABLE_SITES = "test_sites";

    static final String[] TABLE_NAMES = new String[] { TABLE_CAL_DT, TABLE_CATEGORY_GROUPINGS, TABLE_KYLIN_FACT, TABLE_SELLER_TYPE_DIM, TABLE_SITES };

    public static void prepareTestData(String joinType, String cubeName) throws Exception {
        // data is generated according to cube descriptor and saved in resource store
        if (joinType.equalsIgnoreCase("inner")) {
            FactTableGenerator.generate(cubeName, "10000", "1", null, "inner");
        } else if (joinType.equalsIgnoreCase("left")) {
            FactTableGenerator.generate(cubeName, "10000", "0.6", null, "left");
        } else {
            throw new IllegalArgumentException("Unsupported join type : " + joinType);
        }

        deployHiveTables();
    }

    private static void deployHiveTables() throws Exception {

        MetadataManager metaMgr = MetadataManager.getInstance(config());

        // scp data files, use the data from hbase, instead of local files
        File temp = File.createTempFile("temp", ".csv");
        temp.createNewFile();
        for (String tablename : TABLE_NAMES) {
            tablename = tablename.toUpperCase();

            File localBufferFile = new File(temp.getParent() + "/" + tablename + ".csv");
            localBufferFile.createNewFile();

            InputStream hbaseDataStream = metaMgr.getStore().getResource("/data/" + tablename + ".csv");
            FileOutputStream localFileStream = new FileOutputStream(localBufferFile);
            IOUtils.copy(hbaseDataStream, localFileStream);

            hbaseDataStream.close();
            localFileStream.close();

            config().getCliCommandExecutor().copyFile(localBufferFile.getPath(), config().getCliWorkingDir());
            localBufferFile.delete();
        }
        temp.delete();

        // create hive tables
        execHiveCommand(generateCreateTableHql(metaMgr.getTableDesc(TABLE_CAL_DT.toUpperCase())));
        execHiveCommand(generateCreateTableHql(metaMgr.getTableDesc(TABLE_CATEGORY_GROUPINGS.toUpperCase())));
        execHiveCommand(generateCreateTableHql(metaMgr.getTableDesc(TABLE_KYLIN_FACT.toUpperCase())));
        execHiveCommand(generateCreateTableHql(metaMgr.getTableDesc(TABLE_SELLER_TYPE_DIM.toUpperCase())));
        execHiveCommand(generateCreateTableHql(metaMgr.getTableDesc(TABLE_SITES.toUpperCase())));

        // load data to hive tables
        // LOAD DATA LOCAL INPATH 'filepath' [OVERWRITE] INTO TABLE tablename
        execHiveCommand(generateLoadDataHql(TABLE_CAL_DT));
        execHiveCommand(generateLoadDataHql(TABLE_CATEGORY_GROUPINGS));
        execHiveCommand(generateLoadDataHql(TABLE_KYLIN_FACT));
        execHiveCommand(generateLoadDataHql(TABLE_SELLER_TYPE_DIM));
        execHiveCommand(generateLoadDataHql(TABLE_SITES));
    }

    private static void execHiveCommand(String hql) throws IOException {
        String hiveCmd = "hive -e \"" + hql + "\"";
        config().getCliCommandExecutor().execute(hiveCmd);
    }

    private static String generateLoadDataHql(String tableName) {
        return "LOAD DATA LOCAL INPATH '" + config().getCliWorkingDir() + "/" + tableName.toUpperCase() + ".csv' OVERWRITE INTO TABLE " + tableName.toUpperCase();
    }

    private static String generateCreateTableHql(TableDesc tableDesc) {
        StringBuilder ddl = new StringBuilder();

        ddl.append("DROP TABLE IF EXISTS " + tableDesc.getName() + ";\n");
        ddl.append("CREATE TABLE " + tableDesc.getName() + "\n");
        ddl.append("(" + "\n");

        for (int i = 0; i < tableDesc.getColumns().length; i++) {
            ColumnDesc col = tableDesc.getColumns()[i];
            if (i > 0) {
                ddl.append(",");
            }
            ddl.append(col.getName() + " " + SqlHiveDataTypeMapping.getHiveDataType((col.getDatatype())) + "\n");
        }

        ddl.append(")" + "\n");
        ddl.append("ROW FORMAT DELIMITED FIELDS TERMINATED BY ','" + "\n");
        ddl.append("STORED AS TEXTFILE;");

        return ddl.toString();
    }

}