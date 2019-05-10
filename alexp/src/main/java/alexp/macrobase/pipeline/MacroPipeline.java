package alexp.macrobase.pipeline;

import alexp.macrobase.evaluation.GridSearch;
import alexp.macrobase.evaluation.memory.BasicMemoryProfiler;
import alexp.macrobase.outlier.Trainable;
import alexp.macrobase.pipeline.benchmark.config.AlgorithmConfig;
import alexp.macrobase.pipeline.benchmark.config.GridSearchConfig;
import alexp.macrobase.pipeline.benchmark.result.ExecutionResult;
import alexp.macrobase.pipeline.benchmark.result.ResultFileWriter;
import alexp.macrobase.pipeline.benchmark.result.ResultWriter;
import alexp.macrobase.pipeline.config.StringObjectMap;
import alexp.macrobase.utils.BenchmarkUtils;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import edu.stanford.futuredata.macrobase.analysis.classify.Classifier;
import edu.stanford.futuredata.macrobase.datamodel.DataFrame;
import edu.stanford.futuredata.macrobase.datamodel.Schema;
import alexp.macrobase.explanation.Explanation;
import alexp.macrobase.pipeline.benchmark.config.BenchmarkConfig;
import alexp.macrobase.pipeline.benchmark.result.ResultHolder;
import alexp.macrobase.streaming.StreamGenerator;
import alexp.macrobase.streaming.Windows.WindowManager;
import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static alexp.macrobase.utils.BenchmarkUtils.aucCurve;

public class MacroPipeline extends Pipeline {

    private final BenchmarkConfig conf;
    private final String confFileName;
    private final String rootDataDir;
    private ResultWriter resultWriter;
    private final String timeColumn = "__autogenerated_time";
    DataFrame dataFrame;
    int[] labels;


    public MacroPipeline(BenchmarkConfig conf, String confFilePath) {
        this(conf, confFilePath, null, null);
    }

    public MacroPipeline(BenchmarkConfig conf, String confFilePath, String rootDataDir) {
        this(conf, confFilePath, rootDataDir, null);
    }

    public MacroPipeline(BenchmarkConfig conf, String confFilePath, String rootDataDir, ResultWriter resultWriter) {
        this.conf = conf;
        this.confFileName = FilenameUtils.getBaseName(confFilePath);
        this.rootDataDir = rootDataDir;
        this.resultWriter = resultWriter;
    }


    public void classificationMode() throws Exception {

        for (AlgorithmConfig classifierConf : conf.getClassifierConfigs()) {

            printInfo(String.format("Running %s %s on %s", classifierConf.getAlgorithmId(), classifierConf.getParameters(), conf.getDatasetConfig().getUri().getOriginalString()));
            setupResultWriter(getClassificationBaseFilePath(classifierConf).toString());

            dataFrame = loadData();
            labels = getLabels(dataFrame);
            StringObjectMap algorithmParameters = getAlgorithmParameters(classifierConf);

            if (!algorithmParameters.equals(classifierConf.getParameters())) {
                out.println(algorithmParameters);
            }
            Classifier classifier = Pipelines.getClassifier(classifierConf.getAlgorithmId(), algorithmParameters, conf.getDatasetConfig().getMetricColumns());
            ResultHolder resultHolder = runClassifier(classifier);

            printInfo(String.format("Training time: %d ms (%.2f sec), Classification time: %d ms (%.2f sec), Max memory usage: %d MB, ROC AUC: %s, PR AUC: %s",
                    resultHolder.getTrainingTime(), resultHolder.getTrainingTime() / 1000.0,
                    resultHolder.getClassificationTime(), resultHolder.getClassificationTime() / 1000.0,
                    resultHolder.getMaxMemoryUsage() / 1024 / 1024,
                    labels == null ? "n/a" : String.format("%.2f", aucCurve(resultHolder.getResultsDf().getDoubleColumnByName(classifier.getOutputColumnName()), labels).rocArea()),
                    labels == null ? "n/a" : String.format("%.2f", aucCurve(resultHolder.getResultsDf().getDoubleColumnByName(classifier.getOutputColumnName()), labels).prArea())));

            resultWriter.write(resultHolder.getResultsDf(),
                    new ExecutionResult(resultHolder.getTrainingTime(), resultHolder.getClassificationTime(),
                            resultHolder.getMaxMemoryUsage(), conf.getBenchConfForClassifier(classifierConf.getAlgorithmId()), algorithmParameters));

        }

    }


    public void streamingMode() throws Exception {
        for (AlgorithmConfig classifierConf : conf.getClassifierConfigs()) {

            // Initialize window manager
            WindowManager wm = new WindowManager(classifierConf, conf.getDatasetConfig());

            // Make sure that the current classifier is streaming classifier.
            String windowType = wm.getWindowMethod();
            if (windowType.equals("none")) {
                continue;
            }

            // Print the classifier information
            printInfo(String.format("Running %s %s on %s", classifierConf.getAlgorithmId(), classifierConf.getParameters(), conf.getDatasetConfig().getUri().getOriginalString()));
            setupResultWriter(getClassificationBaseFilePath(classifierConf).toString());


            ResultHolder resultHolder = null;
            StringObjectMap algorithmParameters = null;
            Classifier streamingClassifier = null;
            String rawDataPoint = "";


            while (true) {

                if (!wm.windowIsConstructed()) {

                    // Read a raw data point from the Stream Generator
                    rawDataPoint = StreamGenerator.fetch(conf.getDatasetConfig().getUri().getPath());

                    // Obtain the window when the window method invariants are satisfied
                    wm.manage(rawDataPoint);

                    // exit if the real size of the window is zero
                    if(wm.getWindowSize() <= 0){
                        break;
                    }

                } else {

                    // Convert the raw dara into (Macrobase) DataFrame
                    dataFrame = wm.getWindowDF();
                    labels = getLabels(dataFrame);
                    createAutoGeneratedColumns(dataFrame, timeColumn); // needed for MCOD

                    // validate the algorithm parameters
                    algorithmParameters = getAlgorithmParameters(classifierConf);
                    if (!algorithmParameters.equals(classifierConf.getParameters())) {
                        out.println(algorithmParameters);
                    }

                    // Build the Streaming Classifier
                    streamingClassifier = Pipelines.getClassifier(classifierConf.getAlgorithmId(), algorithmParameters, conf.getDatasetConfig().getMetricColumns());

                    // Run the StreamingClassifier
                    resultHolder = runClassifier(streamingClassifier);

                    // Clear the window data (in order to continue to the next window construction)
                    wm.clearWindowData();

                    // Stop iterating when the generator is empty


                    if (wm.isEndStream()) {
                        break;
                    }

                }
            }

            /*
            // print the last resultHolder
            printInfo(String.format("Training time: %d ms (%.2f sec), Classification time: %d ms (%.2f sec), Max memory usage: %d MB, PR AUC: %s",
                    resultHolder.getTrainingTime(), resultHolder.getTrainingTime() / 1000.0,
                    resultHolder.getClassificationTime(), resultHolder.getClassificationTime() / 1000.0,
                    resultHolder.getMaxMemoryUsage() / 1024 / 1024,
                    labels == null ? "n/a" : String.format("%.2f", aucCurve(resultHolder.getResultsDf().getDoubleColumnByName(streamingClassifier.getOutputColumnName()), labels).prArea())));

            resultWriter.write(resultHolder.getResultsDf(),
                    new ExecutionResult(resultHolder.getTrainingTime(), resultHolder.getClassificationTime(),
                            resultHolder.getMaxMemoryUsage(), conf, algorithmParameters));
             */

        }

    }

    public void explanationMode() throws Exception {
        dataFrame = loadData();

        labels = getLabels(dataFrame);

        BasicMemoryProfiler memoryProfiler = new BasicMemoryProfiler();

        for(AlgorithmConfig explainerConf : conf.getExplanationConfigs()){
            printInfo(String.format("Running Explainer %s %s on %s", explainerConf.getAlgorithmId(), explainerConf.getParameters(), conf.getDatasetConfig().getUri().getOriginalString()));
            for(AlgorithmConfig classifierConf: conf.getClassifierConfigs()){
                printInfo(String.format("Running Classifier %s %s\n", classifierConf.getAlgorithmId(), classifierConf.getParameters()));
                setupResultWriter(getExplanationBaseFilePath(explainerConf, classifierConf).toString());
                Explanation explainer = Pipelines.getExplainer(explainerConf, classifierConf, conf.getDatasetConfig().getMetricColumns(), conf.getSettingsConfig().getExplanationSettings());
                final long explanationTime = BenchmarkUtils.measureTime(() -> {
                    explainer.process(dataFrame);
                });
                DataFrame results = explainer.getResults();
                long maxMemoryUsage = memoryProfiler.getPeakUsage();
                System.out.println("\nTime " + explanationTime / 1000.0  + " sec");
//                printInfo(String.format("Explanation time: %d ms (%.2f sec), Max memory usage: %d MB, ROC AUC: %s, PR AUC: %s",
//                        explanationTime, explanationTime / 1000.0,
//                        maxMemoryUsage / 1024 / 1024,
//                        labels == null ? "n/a" : String.format("%.2f", aucCurve(results.getDoubleColumnByName(explainer.getOutputColumnName()), labels).rocArea()),
//                        labels == null ? "n/a" : String.format("%.2f", aucCurve(results.getDoubleColumnByName(explainer.getOutputColumnName()), labels).prArea())));
                resultWriter.write(explainer.getResults(), new ExecutionResult(0,0,0,
                        conf.getBenchConfForExplainer(explainerConf.getAlgorithmId()), explainerConf.getParameters()));
            }
        }
    }

    private ResultHolder runClassifier(Classifier classifier)
            throws Exception {
        BasicMemoryProfiler memoryProfiler = new BasicMemoryProfiler();
        final long trainingTime = classifier instanceof Trainable ? BenchmarkUtils.measureTime(() -> {
            ((Trainable) classifier).train(dataFrame);
        }) : 0;
        final long classificationTime = BenchmarkUtils.measureTime(() -> {
            classifier.process(dataFrame);
        });
        long maxMemoryUsage = memoryProfiler.getPeakUsage();
        DataFrame resultsDf = classifier.getResults();
        return new ResultHolder(resultsDf, trainingTime, classificationTime, maxMemoryUsage);
    }

    private StringObjectMap getAlgorithmParameters(AlgorithmConfig algorithmConfig) throws Exception {
        StringObjectMap baseParams = algorithmConfig.getParameters();
        GridSearchConfig gridSearchConfig = algorithmConfig.getGridSearchConfig();
        if (gridSearchConfig == null) {
            return baseParams;
        }
        out.println(String.format("Running Grid Search, using %s measure", gridSearchConfig.getMeasure().toUpperCase()));
        GridSearch gs = new GridSearch();
        gs.addParams(gridSearchConfig.getParameters());
        gs.setOutputStream(out);
        gs.run(params -> {
            StringObjectMap currentParams = baseParams.merge(params);
            Classifier classifier = Pipelines.getClassifier(algorithmConfig.getAlgorithmId(), currentParams, conf.getDatasetConfig().getMetricColumns());
            classifier.process(dataFrame);
            DataFrame classifierResultDf = classifier.getResults();
            double[] classifierResult = classifierResultDf.getDoubleColumnByName(classifier.getOutputColumnName());
            switch (gridSearchConfig.getMeasure()) {
                case "roc":
                    return aucCurve(classifierResult, labels).rocArea();
                case "pr":
                    return aucCurve(classifierResult, labels).prArea();
                default:
                    throw new RuntimeException("Unknown search measure " + gridSearchConfig.getMeasure());
            }
        });
        SortedMap<Double, Map<String, Object>> gsResults = gs.getResults();
        return baseParams.merge(new StringObjectMap(Iterables.getLast(gsResults.values())));
    }

    private void setupResultWriter(String baseFilePathStr) {
        String finalBaseFilePathStr = getOutputDir().equals(Pipeline.defaultOutputDir()) ? baseFilePathStr : Paths.get(FilenameUtils.getBaseName(Pipeline.defaultOutputDir()), baseFilePathStr).toString();
        resultWriter = new ResultFileWriter()
                .setOutputDir(getOutputDir())
                .setBaseFileName(finalBaseFilePathStr);
    }


    private Path getClassificationBaseFilePath(AlgorithmConfig classifierConfig) {
        return Paths.get(
                FilenameUtils.getBaseName(conf.getDatasetConfig().getDatasetId()),
                getClassificationDir(),
                classifierConfig.getAlgorithmId(),
                confFileName
        );
    }

    private Path getExplanationBaseFilePath(AlgorithmConfig explainerConfig, AlgorithmConfig classifierConfig) {
        return Paths.get(
                FilenameUtils.getBaseName(conf.getDatasetConfig().getDatasetId()),
                getExplanationDir(),
                explainerConfig.getAlgorithmId(),
                classifierConfig.getAlgorithmId(),
                confFileName
        );
    }

    private DataFrame loadData() throws Exception {
        Map<String, Schema.ColType> colTypes = getColTypes();

        List<String> requiredColumns = new ArrayList<>(colTypes.keySet());

        DataFrame dataFrame = Pipelines.loadDataFrame(conf.getDatasetConfig().getUri().addRootPath(rootDataDir), colTypes, requiredColumns, conf.getDatasetConfig().toMap());

        createAutoGeneratedColumns(dataFrame, timeColumn); // needed for MCOD

        return dataFrame;
    }

    private int[] getLabels(DataFrame dataFrame) {
        String labelColumn = conf.getDatasetConfig().getLabelColumn();
        if (Strings.isNullOrEmpty(labelColumn)) {
            return null;
        }

        return Arrays.stream(dataFrame.getDoubleColumnByName(labelColumn))
                .mapToInt(d -> (int) d)
                .toArray();
    }

    private Map<String, Schema.ColType> getColTypes() {
        Map<String, Schema.ColType> colTypes = Arrays.stream(conf.getDatasetConfig().getMetricColumns())
                .collect(Collectors.toMap(Function.identity(), c -> Schema.ColType.DOUBLE));

        if (!Strings.isNullOrEmpty(conf.getDatasetConfig().getLabelColumn())) {
            colTypes.put(conf.getDatasetConfig().getLabelColumn(), Schema.ColType.DOUBLE);
        }

        return colTypes;
    }

}



