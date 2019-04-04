package alexp.macrobase.pipeline.benchmark;

import alexp.macrobase.evaluation.memory.BasicMemoryProfiler;
import alexp.macrobase.outlier.Trainable;
import alexp.macrobase.pipeline.Pipeline;
import alexp.macrobase.pipeline.Pipelines;
import alexp.macrobase.pipeline.benchmark.config.BenchmarkConfig;
import alexp.macrobase.pipeline.benchmark.result.ExecutionResult;
import alexp.macrobase.pipeline.benchmark.result.ResultFileWriter;
import alexp.macrobase.pipeline.benchmark.result.ResultWriter;
import alexp.macrobase.utils.BenchmarkUtils;
import edu.stanford.futuredata.macrobase.analysis.classify.Classifier;
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
        printInfo(String.format("Running %s %s on %s", conf.getAlgorithmConfig().getAlgorithmId(), conf.getAlgorithmConfig().getParameters(), conf.getDatasetConfig().getUri().getOriginalString()));

        if (resultWriter == null) {
            setupResultWriter();
        }

        DataFrame dataFrame = loadDara();

        BasicMemoryProfiler memoryProfiler = new BasicMemoryProfiler();

        Classifier classifier = Pipelines.getClassifier(conf.getAlgorithmConfig().getAlgorithmId(), conf.getAlgorithmConfig().getParameters(), conf.getDatasetConfig().getMetricColumns());

        final long trainingTime = classifier instanceof Trainable ? BenchmarkUtils.measureTime(() -> {
            ((Trainable) classifier).train(dataFrame);
        }) : 0;

        final long classificationTime = BenchmarkUtils.measureTime(() -> {
            classifier.process(dataFrame);
        });

        long maxMemoryUsage = memoryProfiler.getPeakUsage();

        printInfo(String.format("Training time: %d ms (%.2f sec), Classification time: %d ms (%.2f sec), Max memory usage: %d MB",
                trainingTime, trainingTime / 1000.0, classificationTime, classificationTime / 1000.0,
                maxMemoryUsage / 1024 / 1024));

        DataFrame resultsDf = classifier.getResults();

        resultWriter.write(resultsDf, new ExecutionResult(trainingTime, classificationTime, maxMemoryUsage, conf));
    }

    private void setupResultWriter() {
        resultWriter = new ResultFileWriter()
                .setOutputDir(getOutputDir())
                .setBaseFileName(FilenameUtils.removeExtension(conf.getDatasetConfig().getDatasetId()));
    }

    private DataFrame loadDara() throws Exception {
        Map<String, Schema.ColType> colTypes = getColTypes();

        List<String> requiredColumns = new ArrayList<>(colTypes.keySet());

        DataFrame dataFrame = Pipelines.loadDataFrame(conf.getDatasetConfig().getUri().addRootPath(rootDataDir), colTypes, requiredColumns, conf.getDatasetConfig().toMap());

        createAutoGeneratedColumns(dataFrame, timeColumn); // needed for MCOD

        return dataFrame;
    }

    private Map<String, Schema.ColType> getColTypes() {
        return Arrays.stream(conf.getDatasetConfig().getMetricColumns())
                .collect(Collectors.toMap(Function.identity(), c -> Schema.ColType.DOUBLE));
    }
}
