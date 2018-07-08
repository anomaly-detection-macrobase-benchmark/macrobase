package alexp.macrobase.pipeline;

import alexp.macrobase.ingest.StreamingDataFrameLoader;
import alexp.macrobase.ingest.Uri;
import com.google.common.base.Stopwatch;
import edu.stanford.futuredata.macrobase.analysis.classify.Classifier;
import edu.stanford.futuredata.macrobase.analysis.summary.Explanation;
import edu.stanford.futuredata.macrobase.datamodel.DataFrame;
import edu.stanford.futuredata.macrobase.datamodel.Schema;
import edu.stanford.futuredata.macrobase.operator.Operator;
import edu.stanford.futuredata.macrobase.pipeline.PipelineConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StreamingPipeline extends Pipeline {
    private final PipelineConfig conf;

    private final Uri inputURI;

    private String[] metricColumns;

    private boolean isStrPredicate;

    private List<String> attributes;

    private String timeColumn;
    private String idColumn;

    public StreamingPipeline(PipelineConfig conf) {
        this.conf = conf;

        inputURI = new Uri(conf.get("inputURI"));

        //noinspection unchecked
        metricColumns = ((List<String>) conf.get("metricColumns")).toArray(new String[0]);

        String classifierType = conf.get("classifier", "percentile");
        if (classifierType.equals("predicate")) {
            Object rawCutoff = conf.get("cutoff");
            isStrPredicate = rawCutoff instanceof String;
        }

        attributes = conf.get("attributes");

        idColumn = conf.get("idColumn");
        timeColumn = conf.get("timeColumn");
    }

    public void run(Consumer<Explanation> resultCallback) throws Exception {
        StreamingDataFrameLoader dataLoader = getDataLoader();

        Classifier classifier = Pipelines.getClassifier(conf, metricColumns);
        Operator<DataFrame, ? extends Explanation> summarizer = Pipelines.getSummarizer(conf, classifier.getOutputColumnName(), attributes);

        AtomicLong totalClassifierMs = new AtomicLong();
        AtomicLong totalExplanationMs = new AtomicLong();

        AtomicLong batchIndex = new AtomicLong();

        dataLoader.load(dataFrame -> {
            batchIndex.incrementAndGet();

            createAutoGeneratedColumns(dataFrame, timeColumn);

            Stopwatch sw = Stopwatch.createStarted();

            classifier.process(dataFrame);

            final long classifierMs = sw.elapsed(TimeUnit.MILLISECONDS);
            totalClassifierMs.addAndGet(classifierMs);

            saveOutliers("outliers" + batchIndex.get(), classifier);

            sw = Stopwatch.createStarted();

            summarizer.process(classifier.getResults());

            final long explanationMs = sw.elapsed(TimeUnit.MILLISECONDS);
            totalExplanationMs.addAndGet(explanationMs);

            System.out.printf("Classification time: %d ms (total %d ms)\nSummarization time: %d ms (total %d ms)\n\n",
                    classifierMs, totalClassifierMs.get(), explanationMs, totalExplanationMs.get());

            resultCallback.accept(summarizer.getResults());
        });
    }

    private StreamingDataFrameLoader getDataLoader() throws Exception {
        Map<String, Schema.ColType> colTypes = new HashMap<>();
        Schema.ColType metricType = isStrPredicate ? Schema.ColType.STRING : Schema.ColType.DOUBLE;
        for (String column : metricColumns) {
            colTypes.put(column, metricType);
        }

        List<String> requiredColumns = Stream.concat(attributes.stream(), colTypes.keySet().stream()).collect(Collectors.toList());
        requiredColumns.add(idColumn);

        return Pipelines.getStreamingDataLoader(inputURI, colTypes, requiredColumns, conf);
    }
}
