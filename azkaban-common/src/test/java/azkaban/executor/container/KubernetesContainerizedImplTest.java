/*
 * Copyright 2020 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package azkaban.executor.container;

import static azkaban.Constants.ConfigurationKeys.AZKABAN_EVENT_REPORTING_CLASS_PARAM;
import static azkaban.Constants.ConfigurationKeys.AZKABAN_EVENT_REPORTING_ENABLED;
import static azkaban.Constants.EventReporterConstants.EXECUTION_ID;
import static azkaban.Constants.EventReporterConstants.FLOW_STATUS;
import static azkaban.Constants.EventReporterConstants.VERSION_SET;
import static azkaban.ServiceProvider.SERVICE_PROVIDER;
import static azkaban.executor.container.ContainerImplUtils.getJobTypeUsersForFlow;
import static azkaban.executor.container.ContainerImplUtils.populateProxyUsersForFlow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import azkaban.AzkabanCommonModule;
import azkaban.Constants;
import azkaban.Constants.ContainerizedDispatchManagerProperties;
import azkaban.Constants.FlowParameters;
import azkaban.DispatchMethod;
import azkaban.container.models.AzKubernetesV1PodBuilder;
import azkaban.container.models.AzKubernetesV1PodTemplate;
import azkaban.container.models.PodTemplateMergeUtils;
import azkaban.db.DatabaseOperator;
import azkaban.executor.ExecutableFlow;
import azkaban.executor.ExecutableNode;
import azkaban.executor.ExecutionOptions;
import azkaban.executor.ExecutorLoader;
import azkaban.executor.FlowStatusChangeEventListener;
import azkaban.executor.Status;
import azkaban.flow.Flow;
import azkaban.flow.FlowResourceRecommendation;
import azkaban.imagemgmt.converters.Converter;
import azkaban.imagemgmt.converters.ImageRampupPlanConverter;
import azkaban.imagemgmt.converters.ImageTypeConverter;
import azkaban.imagemgmt.converters.ImageVersionConverter;
import azkaban.imagemgmt.daos.ImageRampupDao;
import azkaban.imagemgmt.daos.ImageRampupDaoImpl;
import azkaban.imagemgmt.daos.ImageTypeDao;
import azkaban.imagemgmt.daos.ImageTypeDaoImpl;
import azkaban.imagemgmt.daos.ImageVersionDao;
import azkaban.imagemgmt.daos.ImageVersionDaoImpl;
import azkaban.imagemgmt.dto.ImageRampupPlanRequestDTO;
import azkaban.imagemgmt.dto.ImageRampupPlanResponseDTO;
import azkaban.imagemgmt.dto.ImageTypeDTO;
import azkaban.imagemgmt.dto.ImageVersionDTO;
import azkaban.imagemgmt.models.ImageRampupPlan;
import azkaban.imagemgmt.models.ImageType;
import azkaban.imagemgmt.models.ImageVersion;
import azkaban.imagemgmt.models.ImageVersion.State;
import azkaban.imagemgmt.rampup.ImageRampupManager;
import azkaban.imagemgmt.rampup.ImageRampupManagerImpl;
import azkaban.imagemgmt.version.JdbcVersionSetLoader;
import azkaban.imagemgmt.version.VersionInfo;
import azkaban.imagemgmt.version.VersionSet;
import azkaban.imagemgmt.version.VersionSetBuilder;
import azkaban.imagemgmt.version.VersionSetLoader;
import azkaban.metrics.ContainerizationMetrics;
import azkaban.metrics.DummyContainerizationMetricsImpl;
import azkaban.project.FlowLoaderUtils;
import azkaban.project.Project;
import azkaban.project.ProjectLoader;
import azkaban.project.ProjectManager;
import azkaban.test.Utils;
import azkaban.utils.JSONUtils;
import azkaban.utils.Props;
import azkaban.utils.TestUtils;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.util.Yaml;
import java.io.IOException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class covers unit tests for KubernetesContainerizedImpl class.
 */
public class KubernetesContainerizedImplTest {

  private static final Props props = new Props();
  private KubernetesContainerizedImpl kubernetesContainerizedImpl;
  private ExecutorLoader executorLoader;
  private ProjectLoader projectLoader;
  private VPARecommender vpaRecommender;
  private ApiClient client;
  private static DatabaseOperator dbOperator;
  private VersionSetLoader loader;
  private static ImageRampupManager imageRampupManager;
  private static ImageTypeDao imageTypeDao;
  private static ImageVersionDao imageVersionDao;
  private static ImageRampupDao imageRampupDao;
  private static final String TEST_JSON_DIR = "image_management/k8s_dispatch_test";
  private static final String CPU_REQUESTED_IN_PROPS = "2";
  private static final String MEMORY_REQUESTED_IN_PROPS = "4Gi";
  private static final String MIN_ALLOWED_CPU = "400m";
  private static final String MIN_ALLOWED_MEMORY = "1Gi";
  private static final String MAX_ALLOWED_CPU = "4";
  private static final String MAX_ALLOWED_MEMORY = "32Gi";
  private static final FlowResourceRecommendation DEFAULT_FLOW_RECOMMENDATION =
      new FlowResourceRecommendation(1, 1, "flow", null, null, null);
  private static final ConcurrentHashMap<String, FlowResourceRecommendation> DEFAULT_FLOW_RECOMMENDATION_MAP = new ConcurrentHashMap<String, FlowResourceRecommendation>() {{
    put(DEFAULT_FLOW_RECOMMENDATION.getFlowId(), DEFAULT_FLOW_RECOMMENDATION);
  }};

  public static final String DEPENDENCY1 = "dependency1";
  public static final int CPU_LIMIT_MULTIPLIER = 1;
  public static final int MEMORY_LIMIT_MULTIPLIER = 1;
  public static final String JOBTYPE_PROXY_USER_MAP = "jobtype1,"
    + "jobtype1_proxyuser;jobtype2,jobtype2_proxyuser";
  private static Converter<ImageTypeDTO, ImageTypeDTO,
      ImageType> imageTypeConverter;
  private static Converter<ImageVersionDTO, ImageVersionDTO,
      ImageVersion> imageVersionConverter;
  private static Converter<ImageRampupPlanRequestDTO, ImageRampupPlanResponseDTO,
      ImageRampupPlan> imageRampupPlanConverter;
  private static FlowStatusChangeEventListener flowStatusChangeEventListener;
  private static ContainerizationMetrics containerizationMetrics;

  private static final Logger log = LoggerFactory.getLogger(KubernetesContainerizedImplTest.class);
  private HashMap<String, String> jobTypePrefetchUserMap;

  @BeforeClass
  public static void setUp() throws Exception {
    dbOperator = Utils.initTestDB();
    setupImageTables();
  }

  @AfterClass
  public static void destroyDB() {
    try {
      dbOperator.update("DROP ALL OBJECTS");
      dbOperator.update("SHUTDOWN");
    } catch (final SQLException e) {
      e.printStackTrace();
    }
  }

  @Before
  public void setup() throws Exception {
    this.props.put(ContainerizedDispatchManagerProperties.KUBERNETES_NAMESPACE, "dev-namespace");
    this.props.put(ContainerizedDispatchManagerProperties.KUBERNETES_KUBE_CONFIG_PATH, "src/test"
        + "/resources/container/kubeconfig");
    this.props.put(ContainerizedDispatchManagerProperties.KUBERNETES_SERVICE_REQUIRED, "true");
    // Do not enable VPA recommender by default in this unit test unless manually ramped up.
    this.props.put(ContainerizedDispatchManagerProperties.KUBERNETES_VPA_RAMPUP, 0);
    this.props.put(ContainerizedDispatchManagerProperties.KUBERNETES_FLOW_CONTAINER_CPU_REQUEST,
        CPU_REQUESTED_IN_PROPS);
    this.props.put(ContainerizedDispatchManagerProperties.KUBERNETES_FLOW_CONTAINER_MEMORY_REQUEST
        , MEMORY_REQUESTED_IN_PROPS);
    this.props.put(ContainerizedDispatchManagerProperties.KUBERNETES_FLOW_CONTAINER_MIN_ALLOWED_CPU,
        MIN_ALLOWED_CPU);
    this.props.put(ContainerizedDispatchManagerProperties.KUBERNETES_FLOW_CONTAINER_MIN_ALLOWED_MEMORY,
        MIN_ALLOWED_MEMORY);
    this.props.put(ContainerizedDispatchManagerProperties.KUBERNETES_FLOW_CONTAINER_MAX_ALLOWED_CPU,
        MAX_ALLOWED_CPU);
    this.props.put(ContainerizedDispatchManagerProperties.KUBERNETES_FLOW_CONTAINER_MAX_ALLOWED_MEMORY,
        MAX_ALLOWED_MEMORY);
    this.jobTypePrefetchUserMap =
        ContainerImplUtils.parseJobTypeUsersForFlow(this.JOBTYPE_PROXY_USER_MAP);
    this.executorLoader = mock(ExecutorLoader.class);
    this.projectLoader = mock(ProjectLoader.class);
    this.vpaRecommender = mock(VPARecommender.class);
    this.loader = new JdbcVersionSetLoader(this.dbOperator);
    this.client = mock(ApiClient.class);
    SERVICE_PROVIDER.unsetInjector();
    SERVICE_PROVIDER.setInjector(getInjector(this.props));
    this.flowStatusChangeEventListener = new FlowStatusChangeEventListener(this.props);
    this.containerizationMetrics = new DummyContainerizationMetricsImpl();
    this.kubernetesContainerizedImpl = new KubernetesContainerizedImpl(this.props,
        this.executorLoader, this.loader, this.imageRampupManager, null,
        flowStatusChangeEventListener, containerizationMetrics, null, this.vpaRecommender,
        this.client);
  }

  /**
   * This test is used to verify that if cpu and memory for a flow container is requested from flow
   * parameter then that is given more precedence over system configuration, the constraints are
   * max allowed cpu and memory set in config.
   *
   * @throws Exception
   */
  @Test
  public void testCPUAndMemoryRequestedInFlowParam() throws Exception {
    // User requested cpu and memory that are below max allowed cpu and memory and exceed min
    // allowed cpu and memory
    final Map<String, String> flowParam = new HashMap<>();
    final String cpuRequestedInFlowParam = "3";
    final String memoryRequestedInFlowParam = "6Gi";
    flowParam.put(FlowParameters.FLOW_PARAM_FLOW_CONTAINER_CPU_REQUEST, cpuRequestedInFlowParam);
    flowParam
        .put(FlowParameters.FLOW_PARAM_FLOW_CONTAINER_MEMORY_REQUEST, memoryRequestedInFlowParam);
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerCPURequest(flowParam, null)
        .equals(cpuRequestedInFlowParam));
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerMemoryRequest(flowParam, null)
        .equals(memoryRequestedInFlowParam));
    // cpu and memory limit are determined dynamically based on requested cpu and memory
    String expectedCPULimit =
        this.kubernetesContainerizedImpl
            .getResourceLimitFromResourceRequest(cpuRequestedInFlowParam, CPU_REQUESTED_IN_PROPS,
                CPU_LIMIT_MULTIPLIER);
    String expectedMemoryLimit =
        this.kubernetesContainerizedImpl
            .getResourceLimitFromResourceRequest(memoryRequestedInFlowParam, MEMORY_REQUESTED_IN_PROPS,
                MEMORY_LIMIT_MULTIPLIER);
    Assert.assertTrue(expectedCPULimit.equals(cpuRequestedInFlowParam));
    Assert.assertTrue(expectedMemoryLimit.equals(memoryRequestedInFlowParam));

    // User requested cpu and memory that exceed max allowed cpu and memory
    final String greaterThanMaxCPURequestedInFlowParam = "5";
    final String greaterThanMaxMemoryRequestedInFlowParam = "80Gi";
    flowParam.put(FlowParameters.FLOW_PARAM_FLOW_CONTAINER_CPU_REQUEST,
        greaterThanMaxCPURequestedInFlowParam);
    flowParam.put(FlowParameters.FLOW_PARAM_FLOW_CONTAINER_MEMORY_REQUEST,
        greaterThanMaxMemoryRequestedInFlowParam);
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerCPURequest(flowParam, null)
        .equals(MAX_ALLOWED_CPU));
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerMemoryRequest(flowParam, null).equals(MAX_ALLOWED_MEMORY));
    // User requested cpu and memory that are below max allowed cpu and memory
    final String lessThanMinCPURequestedInFlowParam = "1m";
    final String lessThanMinMemoryRequestedInFlowParam = "1Mi";
    flowParam.put(FlowParameters.FLOW_PARAM_FLOW_CONTAINER_CPU_REQUEST,
        lessThanMinCPURequestedInFlowParam);
    flowParam.put(FlowParameters.FLOW_PARAM_FLOW_CONTAINER_MEMORY_REQUEST,
        lessThanMinMemoryRequestedInFlowParam);
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerCPURequest(flowParam, null)
        .equals(MIN_ALLOWED_CPU));
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerMemoryRequest(flowParam,
        null).equals(MIN_ALLOWED_MEMORY));
    // cpu and memory limit are determined dynamically based on requested cpu and memory
    expectedCPULimit =
        this.kubernetesContainerizedImpl
            .getResourceLimitFromResourceRequest(MAX_ALLOWED_CPU, CPU_REQUESTED_IN_PROPS,
                CPU_LIMIT_MULTIPLIER);
    expectedMemoryLimit =
        this.kubernetesContainerizedImpl
            .getResourceLimitFromResourceRequest(MAX_ALLOWED_MEMORY, MEMORY_REQUESTED_IN_PROPS,
                MEMORY_LIMIT_MULTIPLIER);
    Assert.assertTrue(expectedCPULimit.equals(MAX_ALLOWED_CPU));
    Assert.assertTrue(expectedMemoryLimit.equals(MAX_ALLOWED_MEMORY));

    // User requested memory of different unit, e.g. Ti, Mi
    final String MemoryRequestedInFlowParam1 = "7600Mi";
    flowParam.put(FlowParameters.FLOW_PARAM_FLOW_CONTAINER_MEMORY_REQUEST,
        MemoryRequestedInFlowParam1);
    // 7600 Mi = 7.6 Gi is smaller than max allowed memory and higher than min allowed memory, user
    // requested memory should/be used
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerMemoryRequest(flowParam,
        null).equals(MemoryRequestedInFlowParam1));
    final String MemoryRequestedInFlowParam2 = "0.1Ti";
    flowParam.put(FlowParameters.FLOW_PARAM_FLOW_CONTAINER_MEMORY_REQUEST,
        MemoryRequestedInFlowParam2);
    // 0.1 Ti = 100 Gi > max allowed memory 32 Gi, user requested memory is replaced by max
    // allowed memory
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerMemoryRequest(flowParam,
        null).equals(MAX_ALLOWED_MEMORY));

    // the memory request set by config should be used to get limit
    expectedMemoryLimit =
        this.kubernetesContainerizedImpl
            .getResourceLimitFromResourceRequest(MEMORY_REQUESTED_IN_PROPS, MEMORY_REQUESTED_IN_PROPS,
                MEMORY_LIMIT_MULTIPLIER);
    Assert.assertTrue(expectedMemoryLimit.equals(MEMORY_REQUESTED_IN_PROPS));
  }

  /**
   * This test is used to verify that if CPU and memory for a flow container is not requested in
   * flow param but is obtained from VPA recommender.
   *
   * @throws Exception
   */
  @Test
  public void testCPUAndMemoryRequestedFromVPARecommender() throws Exception {
    // Fully ramp up VPA so VPA is allowed to give resource recommendations to all flows.
    this.kubernetesContainerizedImpl.setVPARampUp(100);
    final Map<String, String> emptyFlowParam = new HashMap<>();
    final Map<String, String> defaultFlowParam = new HashMap<>();
    final String cpuRequestedInFlowParam = "3";
    final String memoryRequestedInFlowParam = "6Gi";
    defaultFlowParam.put(FlowParameters.FLOW_PARAM_FLOW_CONTAINER_CPU_REQUEST, cpuRequestedInFlowParam);
    defaultFlowParam
        .put(FlowParameters.FLOW_PARAM_FLOW_CONTAINER_MEMORY_REQUEST, memoryRequestedInFlowParam);

    // flow resource recommendation cannot be less than min allowed resource limits
    final FlowResourceRecommendation tooSmallFlowResourceRecommendation = new FlowResourceRecommendation(1, 1, "flow",
        "1m", "1Mi", null);
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerCPURequest(emptyFlowParam,
        tooSmallFlowResourceRecommendation.getCpuRecommendation()).equals(MIN_ALLOWED_CPU));
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerMemoryRequest(emptyFlowParam,
        tooSmallFlowResourceRecommendation.getMemoryRecommendation()).equals(MIN_ALLOWED_MEMORY));

    // flow resource recommendation cannot be greater than max allowed resource limits
    final FlowResourceRecommendation tooLargeFlowResourceRecommendation = new FlowResourceRecommendation(1, 1, "flow",
        "20", "1Ti", null);
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerCPURequest(emptyFlowParam,
        tooLargeFlowResourceRecommendation.getCpuRecommendation()).equals(MAX_ALLOWED_CPU));
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerMemoryRequest(emptyFlowParam,
        tooLargeFlowResourceRecommendation.getMemoryRecommendation()).equals(MAX_ALLOWED_MEMORY));

    // max of flow resource recommendation and flow param will be taken
    final FlowResourceRecommendation lessThanFlowParamFlowResourceRecommendation = new FlowResourceRecommendation(1, 1,
        "flow", "2", "5Gi", null);
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerCPURequest(defaultFlowParam,
        lessThanFlowParamFlowResourceRecommendation.getCpuRecommendation()).equals(cpuRequestedInFlowParam));
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerMemoryRequest(defaultFlowParam,
        lessThanFlowParamFlowResourceRecommendation.getMemoryRecommendation()).equals(memoryRequestedInFlowParam));

    // max of flow resource recommendation and flow param will be taken
    final FlowResourceRecommendation greaterThanFlowParamFlowResourceRecommendation = new FlowResourceRecommendation(1, 1,
        "flow", "4", "7Gi", null);
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerCPURequest(defaultFlowParam,
        greaterThanFlowParamFlowResourceRecommendation.getCpuRecommendation()).equals(greaterThanFlowParamFlowResourceRecommendation.getCpuRecommendation()));
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerMemoryRequest(defaultFlowParam,
        greaterThanFlowParamFlowResourceRecommendation.getMemoryRecommendation()).equals(greaterThanFlowParamFlowResourceRecommendation.getMemoryRecommendation()));

    // Reset it back to 0 for other unit tests.
    this.kubernetesContainerizedImpl.setVPARampUp(0);
  }

  /**
   * This test is used to verify that if CPU and memory for a flow container is not requested
   * either in flow param or VPA recommender but defined in system configuration then that is used.
   *
   * @throws Exception
   */
  @Test
  public void testCPUAndMemoryRequestedFromProperties() throws Exception {
    final Map<String, String> flowParam = new HashMap<>();
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerCPURequest(flowParam,
        null).equals(CPU_REQUESTED_IN_PROPS));
    Assert.assertTrue(this.kubernetesContainerizedImpl.getFlowContainerMemoryRequest(flowParam,
        null).equals(MEMORY_REQUESTED_IN_PROPS));
    // cpu and memory limit are determined dynamically based on requested cpu and memory
    final String expectedCPULimit =
        this.kubernetesContainerizedImpl
            .getResourceLimitFromResourceRequest(CPU_REQUESTED_IN_PROPS, CPU_REQUESTED_IN_PROPS,
                CPU_LIMIT_MULTIPLIER);
    Assert.assertTrue(expectedCPULimit.equals(CPU_REQUESTED_IN_PROPS));
    final String expectedMemoryLimit =
        this.kubernetesContainerizedImpl
            .getResourceLimitFromResourceRequest(MEMORY_REQUESTED_IN_PROPS, MEMORY_REQUESTED_IN_PROPS,
                MEMORY_LIMIT_MULTIPLIER);
    Assert.assertTrue(expectedMemoryLimit.equals(MEMORY_REQUESTED_IN_PROPS));
  }

  /**
   * This test is used to verify that if env variables are set correctly for pod which are set from
   * flow param.
   *
   * @throws Exception
   */
  @Test
  public void testPodEnvVariablesFromFlowParam() throws Exception {
    final String azkabanBaseVersion = "azkaban-base.version";
    final String azkabanConfigVersion = "azkaban-config.version";
    final Map<String, String> flowParam = new HashMap<>();
    flowParam.put(FlowParameters.FLOW_PARAM_POD_ENV_VAR + azkabanBaseVersion, "1.0.0");
    flowParam.put(FlowParameters.FLOW_PARAM_POD_ENV_VAR + azkabanConfigVersion, "0.1.0");
    flowParam.put("any.other.param", "test");
    final Map<String, String> envVariables = new HashMap<>();
    this.kubernetesContainerizedImpl.setupPodEnvVariables(envVariables, flowParam);
    assert (envVariables.size() == 2);
    assert (envVariables.get(azkabanBaseVersion.toUpperCase()).equals("1.0.0"));
    assert (envVariables.get(azkabanConfigVersion.toUpperCase()).equals("0.1.0"));
  }

  /**
   * This test is used to verify that if no env variables is set correctly for pod which are set
   * from flow param.
   *
   * @throws Exception
   */
  @Test
  public void testNoPodEnvVariablesFromFlowParam() throws Exception {
    final Map<String, String> flowParam = new HashMap<>();
    flowParam.put("azkaban-base.version", "1.0.0");
    flowParam.put("azkaban-config.version", "0.1.0");
    flowParam.put("any.other.param", "test");
    final Map<String, String> envVariables = new HashMap<>();
    this.kubernetesContainerizedImpl.setupPodEnvVariables(envVariables, flowParam);
    assert (envVariables.size() == 0);
  }

  @Test
  public void testJobTypesInFlow() throws Exception {
    final ExecutableFlow flow = createTestFlow();
    flow.setSubmitUser("testUser1");
    flow.setStatus(Status.PREPARING);
    flow.setSubmitTime(System.currentTimeMillis());
    flow.setExecutionId(0);
    final TreeSet<String> jobTypes = ContainerImplUtils.getJobTypesForFlow(flow);
    assertThat(jobTypes.size()).isEqualTo(1);
  }

  @Test
  public void testNoJobTypeIfJobIsDisabledInFlow() throws Exception {
    final ExecutableFlow flow = createTestFlow();
    flow.setSubmitUser("testUser1");
    flow.setStatus(Status.PREPARING);
    flow.setSubmitTime(System.currentTimeMillis());
    flow.setExecutionId(0);
    // Set all jobs in the flow to be in DISABLED status
    flow.getExecutableNodes().forEach(executableNode -> executableNode.setStatus(Status.DISABLED));
    final TreeSet<String> jobTypes = ContainerImplUtils.getJobTypesForFlow(flow);
    assertThat(jobTypes.size()).isEqualTo(0);
  }

  @Test
  public void testPodConstruction() throws Exception {
    final ExecutableFlow flow = createFlowWithMultipleJobtypes();
    flow.setExecutionId(1);
    when(this.executorLoader.fetchExecutableFlow(flow.getExecutionId())).thenReturn(flow);
    when(imageRampupManager.getVersionByImageTypes(any(), any(Set.class), any(Set.class)))
        .thenReturn(getVersionMap());
    final TreeSet<String> jobTypes = ContainerImplUtils.getJobTypesForFlow(flow);
    final Set<String> dependencyTypes = ImmutableSet.of(DEPENDENCY1);
    assert (jobTypes.contains("command"));
    assert (jobTypes.contains("hadoopJava"));
    assert (jobTypes.contains("spark"));
    log.info("Jobtypes for flow {} are: {}", flow.getFlowId(), jobTypes);

    final Map<String, String> flowParam = new HashMap<>();  // empty map
    final Set<String> allImageTypes = new TreeSet<>();
    allImageTypes.add(KubernetesContainerizedImpl.DEFAULT_AZKABAN_BASE_IMAGE_NAME);
    allImageTypes.add(KubernetesContainerizedImpl.DEFAULT_AZKABAN_CONFIG_IMAGE_NAME);
    allImageTypes.addAll(jobTypes);
    allImageTypes.addAll(dependencyTypes);
    final VersionSet versionSet = this.kubernetesContainerizedImpl
        .fetchVersionSet(flow.getExecutionId(), flowParam, allImageTypes, flow);
    final V1PodSpec podSpec = this.kubernetesContainerizedImpl
        .createPodSpec(flow, DEFAULT_FLOW_RECOMMENDATION, DEFAULT_FLOW_RECOMMENDATION_MAP,
            versionSet, jobTypes, dependencyTypes, flowParam);
    final V1ObjectMeta podMetadata =
        this.kubernetesContainerizedImpl.createPodMetadata(flow, DEFAULT_FLOW_RECOMMENDATION.getId(),
            flowParam);
    // Set custom labels and annotations
    ImmutableMap<String, String> annotations = ImmutableMap.of(
        "akey1", "aval1",
        "akey2", "aval2");
    ImmutableMap<String, String> labels = ImmutableMap.of(
        "lkey1", "lvalue1",
        "lkey2", "lvalue2");
    podMetadata.getAnnotations().putAll(annotations);
    podMetadata.getLabels().putAll(labels);

    assert (podSpec != null);
    assert (podMetadata != null);
    // Verifying if empty string were removed from the list

    final V1Pod pod1 =
        this.kubernetesContainerizedImpl.createPodFromMetadataAndSpec(podMetadata, podSpec);
    final String createdPodSpec1 = Yaml.dump(pod1).trim();
    String readPodSpec1 = TestUtils.readResource("v1PodTest1.yaml", this).trim();
    log.info("Resulting pod spec: {}", flow.getExecutionId(), createdPodSpec1);
    Assert.assertEquals(readPodSpec1, createdPodSpec1);
    log.info("Resulting pod spec: {}", flow.getExecutionId(), createdPodSpec1);

    // Merge the pod created earlier with an externally provided pod template
    AzKubernetesV1PodTemplate podTemplate = AzKubernetesV1PodTemplate.getInstance(
        this.getClass().getResource("v1PodTestTemplate1.yaml").getFile());
    V1PodSpec podSpecFromTemplate = podTemplate.getPodSpecFromTemplate();
    V1ObjectMeta podMetadataFromTemplate = podTemplate.getPodMetadataFromTemplate();
    PodTemplateMergeUtils.mergePodSpec(podSpec, podSpecFromTemplate);
    PodTemplateMergeUtils.mergePodMetadata(podMetadata, podMetadataFromTemplate);
    V1Pod pod2 = new AzKubernetesV1PodBuilder(podMetadata, podSpec).build();
    String createdPodSpec2 = Yaml.dump(pod2).trim();
    String readPodSpec2 = TestUtils.readResource("v1PodTest2.yaml", this).trim();
    Assert.assertEquals(readPodSpec2, createdPodSpec2);
    log.info("Resulting pod spec merged with template: {}", createdPodSpec2);

    // Verify that the number of "volumeMounts" in podSpecFromTemplate after merge is 4
    // (1 from v1PodTestTemplate1.yaml + 3 from v1PodTest1.yaml)
    Assert.assertEquals(4, podSpecFromTemplate.getContainers().get(0)
        .getVolumeMounts().size());

    // Verify that the number of "volumeMounts" in a new podSpecFromTemplate is 2 and hence
    // it is not corrupted by the previous merge.
    podTemplate = AzKubernetesV1PodTemplate.getInstance(
        this.getClass().getResource("v1PodTestTemplate1.yaml").getFile());
    podSpecFromTemplate = podTemplate.getPodSpecFromTemplate();
    Assert.assertEquals(2, podSpecFromTemplate.getContainers().get(0)
        .getVolumeMounts().size());
  }

  @Test
  public void testVersionSetConstructionWithFlowOverrideParams() throws Exception {
    final ExecutableFlow flow = createFlowWithMultipleJobtypes();
    flow.setExecutionId(2);
    when(this.executorLoader.fetchExecutableFlow(flow.getExecutionId())).thenReturn(flow);
    when(imageRampupManager.getVersionByImageTypes(any(), any(Set.class), any(Set.class)))
        .thenReturn(getVersionMap());
    when(imageRampupManager
        .getVersionInfo(any(String.class), any(String.class), any(Set.class)))
        .thenReturn(new VersionInfo("7.0.4", "path1", State.ACTIVE));
    final TreeSet<String> jobTypes = ContainerImplUtils.getJobTypesForFlow(flow);
    // Add included job types
    jobTypes.add("hadoopJava");
    jobTypes.add("pig");
    jobTypes.add("pigLi-0.11.1");
    // Add azkaban base image and config
    final Set<String> allImageTypes = new TreeSet<>();
    allImageTypes.add(KubernetesContainerizedImpl.DEFAULT_AZKABAN_BASE_IMAGE_NAME);
    allImageTypes.add(KubernetesContainerizedImpl.DEFAULT_AZKABAN_CONFIG_IMAGE_NAME);
    allImageTypes.addAll(jobTypes);
    final VersionSetBuilder versionSetBuilder = new VersionSetBuilder(this.loader);
    final VersionSet presetVersionSet = versionSetBuilder
        .addElement("azkaban-base", new VersionInfo("7.0.4", "path1", State.ACTIVE))
        .addElement("azkaban-config", new VersionInfo("9.1.1", "path2", State.ACTIVE))
        .addElement("spark", new VersionInfo("8.0", "path3", State.ACTIVE))
        .addElement("kafkaPush", new VersionInfo("7.1", "path4", State.ACTIVE))
        .build();

    final Map<String, String> flowParam = new HashMap<>();
    flowParam.put(Constants.FlowParameters.FLOW_PARAM_VERSION_SET_ID,
        String.valueOf(presetVersionSet.getVersionSetId()));
    VersionSet versionSet = this.kubernetesContainerizedImpl
        .fetchVersionSet(flow.getExecutionId(), flowParam, allImageTypes, flow);

    assert (versionSet.getVersion("kafkaPush").get()
        .equals(presetVersionSet.getVersion("kafkaPush").get()));
    assert (versionSet.getVersion("spark").get()
        .equals(presetVersionSet.getVersion("spark").get()));
    assert (versionSet.getVersion("azkaban-base").get()
        .equals(presetVersionSet.getVersion("azkaban-base").get()));
    assert (versionSet.getVersion("azkaban-config").get()
        .equals(presetVersionSet.getVersion("azkaban-config").get()));
    // Included jobs in the azkaban base image, so must not present in versionSet.
    Assert.assertEquals(false, versionSet.getVersion("hadoopJava").isPresent());
    Assert.assertEquals(false, versionSet.getVersion("pig").isPresent());
    Assert.assertEquals(false, versionSet.getVersion("pigLi-0.11.1").isPresent());

    // Now let's try constructing an incomplete versionSet
    final VersionSetBuilder incompleteVersionSetBuilder = new VersionSetBuilder(this.loader);
    final VersionSet incompleteVersionSet = incompleteVersionSetBuilder
        .addElement("kafkaPush", new VersionInfo("7.1", "path1", State.ACTIVE))
        .addElement("spark", new VersionInfo("8.0", "path2", State.ACTIVE))
        .build();

    flowParam.put(Constants.FlowParameters.FLOW_PARAM_VERSION_SET_ID,
        String.valueOf(incompleteVersionSet.getVersionSetId()));
    flowParam.put(String.join(".",
        KubernetesContainerizedImpl.IMAGE, "azkaban-base", KubernetesContainerizedImpl.VERSION),
        "7.0.4");
    versionSet = this.kubernetesContainerizedImpl
        .fetchVersionSet(flow.getExecutionId(), flowParam, allImageTypes, flow);

    assert (versionSet.getVersion("kafkaPush").get()
        .equals(presetVersionSet.getVersion("kafkaPush").get()));
    assert (versionSet.getVersion("spark").get()
        .equals(presetVersionSet.getVersion("spark").get()));
    assert (versionSet.getVersion("azkaban-base").get().getVersion().equals("7.0.4"));
    assert (versionSet.getVersion("azkaban-config").get().getVersion().equals("9.1.1"));
  }

  @Test
  public void testVersionSetConstructionWithRampupManager() throws Exception {
    final ExecutableFlow flow = createFlowWithMultipleJobtypes();
    flow.setExecutionId(2);
    when(this.executorLoader.fetchExecutableFlow(flow.getExecutionId())).thenReturn(flow);
    when(imageRampupManager.getVersionByImageTypes(any(), any(Set.class), any(Set.class)))
        .thenReturn(getVersionMap());
    final TreeSet<String> jobTypes = ContainerImplUtils.getJobTypesForFlow(flow);
    final Set<String> dependencyTypes = ImmutableSet.of(DEPENDENCY1);
    // Add included job types
    jobTypes.add("hadoopJava");
    jobTypes.add("pig");
    jobTypes.add("pigLi-0.11.1");
    jobTypes.add("noop");
    // Add azkaban base image and config
    final Set<String> allImageTypes = new TreeSet<>();
    allImageTypes.add(KubernetesContainerizedImpl.DEFAULT_AZKABAN_BASE_IMAGE_NAME);
    allImageTypes.add(KubernetesContainerizedImpl.DEFAULT_AZKABAN_CONFIG_IMAGE_NAME);
    allImageTypes.addAll(jobTypes);
    allImageTypes.addAll(dependencyTypes);

    final Map<String, String> flowParam = new HashMap<>();

    final VersionSet versionSet = this.kubernetesContainerizedImpl
        .fetchVersionSet(flow.getExecutionId(), flowParam, allImageTypes, flow);

    // Included jobs in the azkaban base image, so must not present in versionSet.
    Assert.assertEquals(false, versionSet.getVersion("hadoopJava").isPresent());
    Assert.assertEquals(false, versionSet.getVersion("pig").isPresent());
    Assert.assertEquals(false, versionSet.getVersion("pigLi-0.11.1").isPresent());
    Assert.assertEquals(false, versionSet.getVersion("noop").isPresent());
    Assert.assertEquals("7.0.4", versionSet.getVersion("azkaban-base").get().getVersion());
    Assert.assertEquals("9.1.1", versionSet.getVersion("azkaban-config").get().getVersion());
    Assert.assertEquals("8.0", versionSet.getVersion("spark").get().getVersion());
    Assert.assertEquals("7.1", versionSet.getVersion("kafkaPush").get().getVersion());
    Assert.assertEquals("6.4", versionSet.getVersion("dependency1").get().getVersion());
  }

  @Test
  public void testPopulatingProxyUsersFromProject() throws Exception {
    final ExecutableFlow flow = createTestFlow();
    flow.setProjectId(1);
    ProjectManager projectManager = mock(ProjectManager.class);
    Project project = mock(Project.class);
    Flow flowObj = mock(Flow.class);
    when(flowObj.toString()).thenReturn(flow.getFlowName());
    Set<String> proxyUsers = new HashSet<>();

    ExecutableNode node1 = new ExecutableNode();
    node1.setId("node1");
    node1.setJobSource("job1");
    node1.setStatus(Status.PREPARING);
    Props currentNodeProps1 = new Props();
    Props currentNodeJobProps1 = new Props();
    currentNodeProps1.put("user.to.proxy", "testUser1");
    currentNodeJobProps1.put("user.to.proxy", "");
    when(projectManager.getProject(flow.getProjectId())).thenReturn(project);
    when(project.getFlow(flow.getFlowId())).thenReturn(flowObj);
    when(projectManager.getProperties(project, flowObj, node1.getId(), node1.getJobSource()))
        .thenReturn(currentNodeProps1);
    when(projectManager.getJobOverrideProperty(project, flowObj, node1.getId(),
        node1.getJobSource()))
        .thenReturn(currentNodeJobProps1);
    populateProxyUsersForFlow(node1, flowObj, project, projectManager, proxyUsers);

    // First test when there's no job override user.
    Assert.assertTrue(proxyUsers.contains("testUser1"));
    Assert.assertEquals(1, proxyUsers.size());
    proxyUsers.clear();

    // Test adding a empty string user
    currentNodeProps1.put("user.to.proxy", "");
    populateProxyUsersForFlow(node1, flowObj, project, projectManager, proxyUsers);
    Assert.assertEquals(0, proxyUsers.size());
    currentNodeJobProps1.put("user.to.proxy", "overrideUser");
    populateProxyUsersForFlow(node1, flowObj, project, projectManager, proxyUsers);

    // Second test when there is a job override user.
    Assert.assertTrue(proxyUsers.contains("overrideUser"));
    Assert.assertEquals(1, proxyUsers.size());

    // Third test : Adding a second node and testing size of proxy user list to test it has
    // overrideUser and testUser2
    ExecutableNode node2 = new ExecutableNode();
    node2.setId("node2");
    node2.setJobSource("job2");
    node2.setStatus(Status.PREPARING);
    Props currentNodeProps2 = new Props();
    Props currentNodeJobProps2 = new Props();
    when(projectManager.getProperties(project, flowObj, node2.getId(), node2.getJobSource()))
        .thenReturn(currentNodeProps2);
    currentNodeProps2.put("user.to.proxy", "testUser2");
    currentNodeJobProps2.put("user.to.proxy", "");
    when(projectManager.getJobOverrideProperty(project, flowObj, node2.getId(),
        node2.getJobSource()))
        .thenReturn(currentNodeJobProps2);
    populateProxyUsersForFlow(node2, flowObj, project, projectManager, proxyUsers);

    Assert.assertTrue(proxyUsers.contains("testUser2"));
    Assert.assertEquals(2, proxyUsers.size());
  }

  @Test
  public void testPopulatingJobTypeUsersForFlow() throws Exception {
    Set<String> proxyUsers;
    TreeSet<String> jobTypes = new TreeSet<>();
    jobTypes.add("jobtype1");
    proxyUsers = getJobTypeUsersForFlow(jobTypePrefetchUserMap, jobTypes);
    Assert.assertTrue(proxyUsers.contains("jobtype1_proxyuser"));
    Assert.assertEquals(1, proxyUsers.size());

    jobTypes.add("jobtype2");
    proxyUsers = getJobTypeUsersForFlow(jobTypePrefetchUserMap, jobTypes);
    Assert.assertTrue(proxyUsers.contains("jobtype1_proxyuser"));
    Assert.assertTrue(proxyUsers.contains("jobtype2_proxyuser"));
    Assert.assertEquals(2, proxyUsers.size());

  }

  /**
   * Test a preparing flow to be executed in a container, whether the information of execution id,
   * version set, flow status, can be processed by a PodEventListener
   *
   * @throws Exception
   */
  @Test
  public void testPreparingFlowEvent() throws Exception {
    final ExecutableFlow flow = createFlowWithMultipleJobtypes();
    flow.setExecutionId(2);
    when(this.executorLoader.fetchExecutableFlow(flow.getExecutionId())).thenReturn(flow);
    when(imageRampupManager.getVersionByImageTypes(any(), any(Set.class), any(Set.class)))
        .thenReturn(getVersionMap());

    final TreeSet<String> jobTypes = ContainerImplUtils.getJobTypesForFlow(flow);

    final Map<String, String> flowParam = new HashMap<>();  // empty map
    final Set<String> allImageTypes = new TreeSet<>();
    allImageTypes.add(KubernetesContainerizedImpl.DEFAULT_AZKABAN_BASE_IMAGE_NAME);
    allImageTypes.add(KubernetesContainerizedImpl.DEFAULT_AZKABAN_CONFIG_IMAGE_NAME);
    allImageTypes.addAll(jobTypes);
    final VersionSet versionSet = this.kubernetesContainerizedImpl
        .fetchVersionSet(flow.getExecutionId(), flowParam, allImageTypes, flow);

    flow.setStatus(Status.PREPARING);
    flow.setVersionSet(versionSet);
    // Test event reported from a pod
    final Map<String, String> metaData = flowStatusChangeEventListener.getFlowMetaData(flow);
    Assert.assertTrue(metaData.get(EXECUTION_ID).equals("2"));
    Assert.assertTrue(metaData.get(FLOW_STATUS).equals("PREPARING"));
    final String versionSetJsonString = metaData.get(VERSION_SET);
    final Map<String, String> imageToVersionMap =
        new ObjectMapper().readValue(versionSetJsonString,
            new TypeReference<HashMap<String, String>>() {
            });
    assertThat(imageToVersionMap.keySet()).isEqualTo(versionSet.getImageToVersionMap().keySet());
    assertThat(imageToVersionMap.get("spark")).isEqualTo(versionSet.getImageToVersionMap()
        .get("spark").getVersion());
    assertThat(imageToVersionMap.get(KubernetesContainerizedImpl.DEFAULT_AZKABAN_BASE_IMAGE_NAME))
        .isEqualTo(versionSet.getImageToVersionMap()
            .get(KubernetesContainerizedImpl.DEFAULT_AZKABAN_BASE_IMAGE_NAME).getVersion());
  }

  /**
   * Test merging of flow properties and flow params.
   * @throws Exception
   */
  @Test
  public void testFlowPropertyAndParamsMerge() throws Exception {
    final ExecutableFlow flow = createTestFlow();
    flow.setExecutionId(3);
    final Props flowProps = new Props();
    flowProps.put("param.override.image.version", "1.2.3");
    flowProps.put("regular.param", "4.5.6"); // Should be filtered out.
    when(this.projectLoader.fetchProjectProperty(
        flow.getProjectId(), flow.getVersion(), Constants.PARAM_OVERRIDE_FILE)).thenReturn(flowProps);
    final ExecutionOptions executionOptions = new ExecutionOptions();
    flow.setExecutionOptions(executionOptions);
    final Map<String, String> flowParams = flow.getExecutionOptions().getFlowParameters();
    Assert.assertEquals(0, flowParams.size());
    // Merge the flow props and flow params
    flow.setFlowParamsFromProps(
        FlowLoaderUtils.loadPropsForExecutableFlow(this.projectLoader, flow));
    final Map<String, String> mergedFlowPropsAndParams = flow.getExecutionOptions().getFlowParameters();
    Assert.assertEquals(1, mergedFlowPropsAndParams.size());
    Assert.assertTrue(mergedFlowPropsAndParams.containsKey("image.version"));
    Assert.assertEquals("1.2.3", mergedFlowPropsAndParams.get("image.version"));
  }

  /**
   * Test merging of flow properties and flow params where flow params override the flow property.
   * @throws Exception
   */
  @Test
  public void testFlowPropertyAndParamsMergeWithOverwrite() throws Exception {
    final ExecutableFlow flow = createTestFlow();
    flow.setExecutionId(3);
    final Props flowProps = new Props();
    flowProps.put("param.override.image.version", "1.2.3");
    flowProps.put("regular.param", "4.5.6"); // Should be filtered out.
    when(this.projectLoader.fetchProjectProperty(
        flow.getProjectId(), flow.getVersion(), Constants.PARAM_OVERRIDE_FILE)).thenReturn(flowProps);
    final ExecutionOptions executionOptions = new ExecutionOptions();
    flow.setExecutionOptions(executionOptions);
    final Map<String, String> flowParams = flow.getExecutionOptions().getFlowParameters();
    Assert.assertEquals(0, flowParams.size());
    // flow params take priority.
    flowParams.put("image.version", "2.3.4");
    // Merge the flow props and flow params
    flow.setFlowParamsFromProps(
        FlowLoaderUtils.loadPropsForExecutableFlow(this.projectLoader, flow));
    final Map<String, String> mergedFlowPropsAndParams = flow.getExecutionOptions().getFlowParameters();
    Assert.assertEquals(1, mergedFlowPropsAndParams.size());
    Assert.assertTrue(mergedFlowPropsAndParams.containsKey("image.version"));
    Assert.assertEquals("1.2.3", mergedFlowPropsAndParams.get("image.version"));
  }

  /**
   * Test merging of flow properties and flow params where props in null.
   * @throws Exception
   */
  @Test
  public void testFlowPropertyAndParamsMergeNull() throws Exception {
    final ExecutableFlow flow = createTestFlow();
    flow.setExecutionId(3);
    when(this.projectLoader.fetchProjectProperty(
        flow.getProjectId(), flow.getVersion(), Constants.PARAM_OVERRIDE_FILE)).thenReturn(null);
    final ExecutionOptions executionOptions = new ExecutionOptions();
    flow.setExecutionOptions(executionOptions);
    final Map<String, String> flowParams = flow.getExecutionOptions().getFlowParameters();
    Assert.assertEquals(0, flowParams.size());
    // flow params take priority.
    flowParams.put("image.version", "2.3.4");
    // Merge the flow props and flow params
    flow.setFlowParamsFromProps(
        FlowLoaderUtils.loadPropsForExecutableFlow(this.projectLoader, flow));
    final Map<String, String> mergedFlowPropsAndParams = flow.getExecutionOptions().getFlowParameters();
    Assert.assertEquals(1, mergedFlowPropsAndParams.size());
    Assert.assertTrue(mergedFlowPropsAndParams.containsKey("image.version"));
    Assert.assertEquals("2.3.4", mergedFlowPropsAndParams.get("image.version"));
  }

  /**
   * Test get execution id set from pod list
   * @throws Exception
   */
  @Test
  public  void testGetExecutionIdFromPodList() throws Exception{
    final V1PodList podList = new V1PodList();
    final OffsetDateTime validStartTimeStamp = OffsetDateTime.now();

    // stale pod 1 with execution id information
    final V1ObjectMeta podMetadata1 = new V1ObjectMeta();
    final ImmutableMap<String, String> label1 = ImmutableMap.of(
        "execution-id", "execid-123");
    podMetadata1.setLabels(label1);
    podMetadata1.setCreationTimestamp(validStartTimeStamp.minus(1, ChronoUnit.MILLIS));
    final V1Pod pod1 = new AzKubernetesV1PodBuilder(podMetadata1, null).build();

    // stale pod 2 without execution id information
    final V1ObjectMeta podMetadata2 = new V1ObjectMeta();
    final ImmutableMap<String, String> label2 = ImmutableMap.of(
        "key2", "val2");
    podMetadata2.setLabels(label2);
    podMetadata2.setCreationTimestamp(validStartTimeStamp.minus(1, ChronoUnit.MILLIS));
    final V1Pod pod2 = new AzKubernetesV1PodBuilder(podMetadata2, null).build();

    // valid pod 3 with execution id information
    final V1ObjectMeta podMetadata3 = new V1ObjectMeta();
    final ImmutableMap<String, String> label3 = ImmutableMap.of(
        "execution-id", "execid-12345");
    podMetadata3.setLabels(label3);
    podMetadata3.setCreationTimestamp(validStartTimeStamp.plus(1, ChronoUnit.MILLIS));
    final V1Pod pod3 = new AzKubernetesV1PodBuilder(podMetadata3, null).build();

    podList.addItemsItem(pod1);
    podList.addItemsItem(pod2);
    podList.addItemsItem(pod3);

    final Set<Integer> staleContainerExecIdSet =
        this.kubernetesContainerizedImpl.getExecutionIdsFromPodList(podList,
        validStartTimeStamp);
    Assert.assertTrue(staleContainerExecIdSet.contains(123));
    Assert.assertFalse(staleContainerExecIdSet.contains(-1));
    Assert.assertFalse(staleContainerExecIdSet.contains(12345));
  }

  private ExecutableFlow createTestFlow() throws Exception {
    return TestUtils.createTestExecutableFlow("exectest1", "exec1", DispatchMethod.CONTAINERIZED);
  }

  private ExecutableFlow createFlowWithMultipleJobtypes() throws Exception {
    return TestUtils.createTestExecutableFlowFromYaml("embedded4", "valid_dag_2");
  }

  private static void setupImageTables() {
    imageTypeDao = new ImageTypeDaoImpl(dbOperator);
    imageVersionDao = new ImageVersionDaoImpl(dbOperator, imageTypeDao);
    imageRampupDao = new ImageRampupDaoImpl(dbOperator, imageTypeDao, imageVersionDao);
    // Create a mock of ImageRampupManager to get the image type and version map. This mock is
    // required as the completed flow of getting image type and version can't be tested by
    // populating image management table due non supported "UNSIGNED" integer in HSQL.
    imageRampupManager = mock(ImageRampupManagerImpl.class);
    imageTypeConverter = new ImageTypeConverter();
    imageVersionConverter = new ImageVersionConverter();
    imageRampupPlanConverter = new ImageRampupPlanConverter();
    final ObjectMapper objectMapper = new ObjectMapper();
    // Insert into all the below image tables is commented as in memory HSQL database does not
    // support "UNSIGNED" data type. Some of the queries in ImageVersionDaoImpl.java uses
    //"UNSIGNED" integer, all the insert entries are commented out and ImageRampupManager mock is
    //create above to get the image type and version map.
    /*addImageTypeTableEntry("image_type_hadoopJava.json", objectMapper);
    addImageTypeTableEntry("image_type_command.json", objectMapper);
    addImageTypeTableEntry("image_type_spark.json", objectMapper);
    addImageVersions("image_types_active_versions.json", objectMapper);
    addImageRampupEntries("create_image_rampup.json", objectMapper);*/
  }

  private static void addImageTypeTableEntry(final String jsonFile,
      final ObjectMapper objectMapper) {
    final String jsonPayload = JSONUtils.readJsonFileAsString(TEST_JSON_DIR + "/" + jsonFile);
    try {
      final ImageTypeDTO imageType = objectMapper.readValue(jsonPayload, ImageTypeDTO.class);
      imageType.setCreatedBy("azkaban");
      imageType.setModifiedBy("azkaban");
      imageTypeDao.createImageType(imageTypeConverter.convertToDataModel(imageType));
    } catch (final Exception e) {
      log.error("Failed to read from json file: " + jsonPayload);
      assert (false);
    }
  }

  private static void addImageVersions(final String jsonFile, final ObjectMapper objectMapper) {
    final String jsonPayload = JSONUtils.readJsonFileAsString(TEST_JSON_DIR + "/" + jsonFile);
    List<ImageVersionDTO> imageVersions = null;
    try {
      imageVersions = objectMapper.readValue(jsonPayload,
          new TypeReference<List<ImageVersionDTO>>() {
          });
      log.info(String.valueOf(imageVersions));
      for (final ImageVersionDTO imageVersion : imageVersions) {
        imageVersion.setCreatedBy("azkaban");
        imageVersion.setModifiedBy("azkaban");
        imageVersionDao.createImageVersion(imageVersionConverter.convertToDataModel(imageVersion));
      }
    } catch (final IOException e) {
      log.error("Exception while converting input json: ", e);
      assert (false);
    }
  }

  private static void addImageRampupEntries(final String jsonFile,
      final ObjectMapper objectMapper) {
    final String jsonPayload = JSONUtils.readJsonFileAsString(TEST_JSON_DIR + "/" + jsonFile);
    List<ImageRampupPlanRequestDTO> imageRampupPlanRequests = null;
    try {
      imageRampupPlanRequests = objectMapper.readValue(jsonPayload,
          new TypeReference<List<ImageRampupPlanRequestDTO>>() {
          });
      for (final ImageRampupPlanRequestDTO imageRampupPlanRequest : imageRampupPlanRequests) {
        imageRampupPlanRequest.setCreatedBy("azkaban");
        imageRampupPlanRequest.setModifiedBy("azkaban");
        imageRampupDao.createImageRampupPlan(
            imageRampupPlanConverter.convertToDataModel(imageRampupPlanRequest));
      }
    } catch (final IOException e) {
      log.error("Exception while converting input json: ", e);
      assert (false);
    }
  }

  /**
   * Creates a version map, which contains key value pairs of image name and corresponding version
   * number
   *
   * @return a version set map
   */
  private Map<String, VersionInfo> getVersionMap() {
    final Map<String, VersionInfo> versionMap = new TreeMap<>();
    versionMap.put(KubernetesContainerizedImpl.DEFAULT_AZKABAN_BASE_IMAGE_NAME,
        new VersionInfo("7.0.4", "path1", State.ACTIVE));
    versionMap.put(KubernetesContainerizedImpl.DEFAULT_AZKABAN_CONFIG_IMAGE_NAME,
        new VersionInfo("9.1.1", "path2", State.ACTIVE));
    versionMap.put("spark", new VersionInfo("8.0", "path3", State.ACTIVE));
    versionMap.put("kafkaPush", new VersionInfo("7.1", "path4", State.ACTIVE));
    versionMap.put(DEPENDENCY1, new VersionInfo("6.4", "path5", State.ACTIVE));
    return versionMap;
  }

  /**
   * Creates a Guice injector for Azkaban event reporter instantiation
   *
   * @param props
   * @return
   */
  private Injector getInjector(final Props props) {
    props.put(AZKABAN_EVENT_REPORTING_ENABLED, "true");
    props.put(AZKABAN_EVENT_REPORTING_CLASS_PARAM,
        "azkaban.project.AzkabanEventReporterTest");
    props.put("database.type", "h2");
    props.put("h2.path", "h2");
    return Guice.createInjector(new AzkabanCommonModule(props));
  }
}
