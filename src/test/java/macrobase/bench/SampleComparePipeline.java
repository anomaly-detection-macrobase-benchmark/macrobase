package macrobase.bench;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import macrobase.analysis.classify.BatchingPercentileClassifier;
import macrobase.analysis.classify.DumpClassifier;
import macrobase.analysis.classify.OutlierClassifier;
import macrobase.analysis.contextualoutlier.Interval;
import macrobase.analysis.pipeline.BasePipeline;
import macrobase.analysis.pipeline.Pipeline;
import macrobase.analysis.result.AnalysisResult;
import macrobase.analysis.result.OutlierClassificationResult;
import macrobase.analysis.stats.BatchTrainScore;
import macrobase.analysis.stats.MinCovDet;
import macrobase.analysis.transform.BatchScoreFeatureTransform;
import macrobase.analysis.transform.FeatureTransform;
import macrobase.analysis.transform.LinearMetricNormalizer;
import macrobase.conf.MacroBaseConf;
import macrobase.datamodel.Datum;
import macrobase.ingest.DataIngester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class SampleComparePipeline extends BasePipeline {
    private static final Logger log = LoggerFactory.getLogger(SampleComparePipeline.class);

    @Override
    public Pipeline initialize(MacroBaseConf conf) throws Exception {
        super.initialize(conf);
        conf.sanityCheckBatch();
        return this;
    }

    @Override
    public List<AnalysisResult> run() throws Exception {
        Stopwatch sw = Stopwatch.createStarted();
        DataIngester ingester = conf.constructIngester();
        FeatureTransform normalizer = new LinearMetricNormalizer();
        normalizer.consume(ingester.getStream().drain());
        List<Datum> data = normalizer.getStream().drain();
        System.gc();

        Map<Long, Datum> originalData = new HashMap<>();
        for(Datum d : data) {
            originalData.put(d.getID(), d);
        }

        BatchTrainScore goldtrain = conf.constructTransform(conf.getTransformType());

        sw.reset();
        sw.start();
        goldtrain.train(data);
        List<Datum> scored = new ArrayList<>(data.size());
        for(Datum d : data) {
            scored.add(new Datum(d, goldtrain.score(d)));
        }
        sw.stop();

        scored.sort((a, b) -> ((Double) a.norm())
                .compareTo(b.norm()));

        OutlierClassifier gold = new BatchingPercentileClassifier(conf);
        gold.consume(scored);
        List<OutlierClassificationResult> goldResult = gold.getStream().drain();

        Set<Long> goldOutliers = Sets.newHashSet(
                goldResult.stream().filter(d -> d.isOutlier()).map(a -> a.getDatum().getParentID()).collect(
                        Collectors.toList()));
        Set<Long> goldInliers = Sets.newHashSet(
                goldResult.stream().filter(d -> !d.isOutlier()).map(a -> a.getDatum().getParentID()).collect(
                        Collectors.toList()));

        Set<Datum> goldInlierData = Sets.newHashSet(
                goldResult.stream().filter(d -> !d.isOutlier()).map(a -> a.getDatum()).collect(Collectors.toList()));


        Map<Long, Double> goldScores = new HashMap<>();
        double goldTotal = 0;
        for(OutlierClassificationResult d : goldResult) {
            goldScores.put(d.getDatum().getParentID(), d.getDatum().norm());
            goldTotal += d.getDatum().norm();
        }

        double[] l = {1e-6, 1e-5, 1e-4, 1e-3, 1e-2, 1e-1, .5, .75, 1};
        final int ITERATIONS = 5;

        for(Double h : l) {
            List<Double> times = new ArrayList<>();
            List<Double> rmses = new ArrayList<>();
            List<Double> precisions = new ArrayList<>();
            List<Double> accuracies = new ArrayList<>();
            List<Double> recalls = new ArrayList<>();
            List<Double> goldInlierRMSEs = new ArrayList<>();

            if(data.size() * h < 10) {
                continue;
            }

            for(int i = 0; i < ITERATIONS; ++i) {
                BatchTrainScore batchTrainScore = conf.constructTransform(conf.getTransformType());

                Collections.shuffle(data);

                List<Datum> sample = data.subList(0, (int) (data.size() * h));

                log.debug("Sample size is {}", sample.size());

                sw.reset();
                sw.start();
                batchTrainScore.train(sample);
                List<Datum> scored2 = new ArrayList<>(data.size());
                for(Datum d : data) {
                    scored2.add(new Datum(d, batchTrainScore.score(d)));
                }
                sw.stop();
                times.add((double) sw.elapsed(TimeUnit.MICROSECONDS));

                scored2.sort((a, b) -> ((Double) a.norm())
                        .compareTo(b.norm()));

                OutlierClassifier thisoc = new BatchingPercentileClassifier(conf);
                thisoc.consume(scored2);
                List<OutlierClassificationResult> curResult = thisoc.getStream().drain();

                double sum_squares = 0;
                for(OutlierClassificationResult d : curResult) {
                    sum_squares += Math.pow(goldScores.get(d.getDatum().getParentID()) - d.getDatum().norm(), 2);
                }

                log.info("sum squares: {}; data size: {}", sum_squares, curResult.size());
                double rmse = Math.sqrt(sum_squares/curResult.size());

                log.info("RMSE: {}", rmse);

                log.info("minscore: {} {}",
                         curResult.get(0).getDatum().norm(),
                         goldResult.get(0).getDatum().norm());

                double sum_squares_go = 0;
                for(Datum d : goldInlierData) {
                    sum_squares_go += Math.pow(goldScores.get(d.getParentID())
                                               - batchTrainScore.score(originalData.get(d.getParentID())), 2);
                }
                double rmse_go = Math.sqrt(sum_squares_go/goldInliers.size());

                log.info("RMSE_GOLD_INLIERS: {}", rmse_go);

                goldInlierRMSEs.add(rmse_go);

                Set<Datum> curOutliers = Sets.newHashSet(
                        curResult.stream().filter(d -> d.isOutlier()).map(a -> a.getDatum()).collect(
                                Collectors.toList()));
                Set<Datum> curInliers = Sets.newHashSet(
                        curResult.stream().filter(d -> !d.isOutlier()).map(a -> a.getDatum()).collect(Collectors.toList()));

                double right = 0;
                for(Datum o : curOutliers) {
                    if(goldOutliers.contains(o.getParentID())) {
                        right += 1;
                    }
                }

                for(Datum o : curInliers) {
                    if(goldInliers.contains(o.getParentID())) {
                        right += 1;
                    }
                }

                double accuracy = right/data.size();
                accuracies.add(accuracy);


                log.info("maxscore: {} {}",
                         curResult.get(curResult.size() - 1).getDatum().norm(),
                         goldResult.get(goldResult.size() - 1).getDatum().norm());

                rmses.add(rmse);


                double intersectionSize = Sets.intersection(curOutliers, goldOutliers).size();
                double precision = intersectionSize / curOutliers.size();
                log.info("intersection size is {} {} {}", intersectionSize, curOutliers.size(), goldOutliers.size());
                double recall = (double) intersectionSize / goldOutliers.size();
                precisions.add(precision);
                recalls.add(recall);
            }

            double avgtime = times.stream().reduce((a, b) -> a+b).get()/times.size();
            double avgrmse = rmses.stream().reduce((a, b) -> a+b).get()/rmses.size();
            double avggoldinlierrmse = goldInlierRMSEs.stream().reduce((a, b) -> a+b).get()/goldInlierRMSEs.size();
            double avgprecision = precisions.stream().reduce((a, b) -> a+b).get()/precisions.size();
            double avgrecall = recalls.stream().reduce((a, b) -> a+b).get()/recalls.size();
            double avgaccuracy = accuracies.stream().reduce((a, b) -> a+b).get()/accuracies.size();


            log.info("h: {}, avgtime:{}, avgrmse: {}, avggoldinlierrmse: {}, avgprecision: {}, avgrecall: {}, avgaccuracy: {}", h, avgtime, avgrmse, avggoldinlierrmse, avgprecision, avgrecall, avgaccuracy);
            log.info("h: {}, times:{}, rmses: {}, goldinlierrmses: {}, precisions: {}, recalls: {}, accuracies: {}, datasize: {}", h, times, rmses, goldInlierRMSEs, precisions, recalls, accuracies, data.size());


        }


        return Arrays.asList(new AnalysisResult(0, 0, 0, 0, 0, new ArrayList<>()));
    }
}