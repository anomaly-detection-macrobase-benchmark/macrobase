package alexp.macrobase.pipeline.benchmark;

import alexp.macrobase.pipeline.Pipeline;
import alexp.macrobase.pipeline.Pipelines;
import alexp.macrobase.pipeline.benchmark.config.BenchmarkConfig;
import alexp.macrobase.pipeline.benchmark.config.ExecutionType;
import alexp.macrobase.pipeline.benchmark.result.ResultFileWriter;
import alexp.macrobase.pipeline.benchmark.result.ResultWriter;
import com.google.common.base.Strings;
import edu.stanford.futuredata.macrobase.datamodel.DataFrame;
import edu.stanford.futuredata.macrobase.datamodel.Schema;
import org.apache.commons.io.FilenameUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ClassifierEvaluationPipeline extends Pipeline {
    private final BenchmarkConfig conf;
    private final String rootDataDir;
    private ResultWriter resultWriter;

    private final String timeColumn = "__autogenerated_time";

    DataFrame dataFrame;
    int[] labels;

    public ClassifierEvaluationPipeline(BenchmarkConfig conf) {
        this(conf, null, null);
    }

    public ClassifierEvaluationPipeline(BenchmarkConfig conf, String rootDataDir) {
        this(conf, rootDataDir, null);
    }

    public ClassifierEvaluationPipeline(BenchmarkConfig conf, String rootDataDir, ResultWriter resultWriter) {
        this.conf = conf;
        this.rootDataDir = rootDataDir;
        this.resultWriter = resultWriter;
    }

    public void run() throws Exception {
//        printInfo(String.format("Running %s %s on %s", conf.getAlgorithmConfig().getAlgorithmId(), conf.getAlgorithmConfig().getParameters(), conf.getDatasetConfig().getUri().getOriginalString()));
//
//        if (resultWriter == null) {
//            setupResultWriter();
//        }
//
//        dataFrame = loadData();
//        labels = getLabels(dataFrame);
//
//        StringObjectMap algorithmParameters = getAlgorithmParameters();
//
//        if (!algorithmParameters.equals(conf.getAlgorithmConfig().getParameters())) {
//            out.println(algorithmParameters);
//        }
//
//        BasicMemoryProfiler memoryProfiler = new BasicMemoryProfiler();
//
//        Classifier classifier = Pipelines.getClassifier(conf.getAlgorithmConfig().getAlgorithmId(), algorithmParameters, conf.getDatasetConfig().getMetricColumns());
//
//        final long trainingTime = classifier instanceof Trainable ? BenchmarkUtils.measureTime(() -> {
//            ((Trainable) classifier).train(dataFrame);
//        }) : 0;
//
//        final long classificationTime = BenchmarkUtils.measureTime(() -> {
//            classifier.process(dataFrame);
//        });
//
//        long maxMemoryUsage = memoryProfiler.getPeakUsage();
//
//        DataFrame resultsDf = classifier.getResults();
//
//        printInfo(String.format("Training time: %d ms (%.2f sec), Classification time: %d ms (%.2f sec), Max memory usage: %d MB, PR AUC: %s",
//                trainingTime, trainingTime / 1000.0, classificationTime, classificationTime / 1000.0,
//                maxMemoryUsage / 1024 / 1024,
//                labels == null ? "n/a" : String.format("%.2f", aucCurve(resultsDf.getDoubleColumnByName(classifier.getOutputColumnName()), labels).prArea())));
//
//        resultWriter.write(resultsDf, new ExecutionResult(trainingTime, classificationTime, maxMemoryUsage, conf, algorithmParameters));
    }

//    private StringObjectMap getAlgorithmParameters() throws Exception {
//        AlgorithmConfig algorithmConfig = conf.getAlgorithmConfig();
//
//        StringObjectMap baseParams = algorithmConfig.getParameters();
//
//        GridSearchConfig gridSearchConfig = algorithmConfig.getGridSearchConfig();
//        if (gridSearchConfig == null) {
//            return baseParams;
//        }
//
//        out.println(String.format("Running Grid Search, using %s measure", gridSearchConfig.getMeasure().toUpperCase()));
//
//        GridSearch gs = new GridSearch();
//        gs.addParams(gridSearchConfig.getParameters());
//        gs.setOutputStream(out);
//
//        gs.run(params -> {
//            StringObjectMap currentParams = baseParams.merge(params);
//
//            Classifier classifier = Pipelines.getClassifier(algorithmConfig.getAlgorithmId(), currentParams, conf.getDatasetConfig().getMetricColumns());
//
//            classifier.process(dataFrame);
//
//            DataFrame classifierResultDf = classifier.getResults();
//            double[] classifierResult = classifierResultDf.getDoubleColumnByName(classifier.getOutputColumnName());
//
//            switch (gridSearchConfig.getMeasure()) {
//                case "roc": return aucCurve(classifierResult, labels).rocArea();
//                case "pr": return aucCurve(classifierResult, labels).prArea();
//                default: throw new RuntimeException("Unknown search measure " + gridSearchConfig.getMeasure());
//            }
//        });
//
//        SortedMap<Double, Map<String, Object>> gsResults = gs.getResults();
//
//        return baseParams.merge(new StringObjectMap(Iterables.getLast(gsResults.values())));
//    }

    private void setupResultWriter() {
        resultWriter = new ResultFileWriter(ExecutionType.BATCH_CLASSIFICATION)
                .setOutputDir(getOutputDir())
                .setBaseFileName(FilenameUtils.removeExtension(conf.getDatasetConfig().getDatasetId()));
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
