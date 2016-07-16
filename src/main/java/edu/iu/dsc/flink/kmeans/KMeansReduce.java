package edu.iu.dsc.flink.kmeans;

import edu.iu.dsc.flink.kmeans.utils.KMeansData;
import org.apache.flink.api.common.functions.*;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.functions.FunctionAnnotation;
import org.apache.flink.api.java.operators.IterativeDataSet;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.util.Collector;

import java.util.Collection;
import java.util.Iterator;

public class KMeansReduce {
  public static void main(String[] args) throws Exception {

    // Checking input parameters
    final ParameterTool params = ParameterTool.fromArgs(args);
    int parallel = params.getInt("parallel", 1);
    // set up execution environment
    ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
    env.getConfig().setGlobalJobParameters(params); // make parameters available in the web interface

    // get input data:
    // read the points and centroids from the provided paths or fall back to default data
    DataSet<Point> points = getPointDataSet(params, env);
    DataSet<Centroid> centroids = getCentroidDataSet(params, env);

    // set number of bulk iterations for KMeans algorithm
    IterativeDataSet<Centroid> loop = centroids.iterate(params.getInt("iterations", 10));

    DataSet<Centroid> newCentroids = points
        // compute closest centroid for each point
        .map(new KMeans.SelectNearestCenter()).withBroadcastSet(loop, "centroids").
            groupBy(0).combineGroup(new GroupCombineFunction<Tuple2<Integer, Point>, Tuple2<Integer, Point>>() {
          @Override
          public void combine(Iterable<Tuple2<Integer, Point>> iterable,
                              Collector<Tuple2<Integer, Point>> collector) throws Exception {
            Iterator<Tuple2<Integer, Point>> it = iterable.iterator();
            int index = -1;
            double x = 0, y = 0;
            int count = 0;
            while (it.hasNext()) {
              Tuple2<Integer, Point> p = it.next();
              x += p.f1.x;
              y += p.f1.y;
              index = p.f0;
              count++;
            }
            collector.collect(new Tuple2<Integer, Point>(index, new Point(x / count, y / count)));
          }
        })
        // count and sum point coordinates for each centroid
        .groupBy(0).reduceGroup(new GroupReduceFunction<Tuple2<Integer, Point>, Centroid>() {
          @Override
          public void reduce(Iterable<Tuple2<Integer, Point>> iterable,
                             Collector<Centroid> collector) throws Exception {
            Iterator<Tuple2<Integer, Point>> it = iterable.iterator();
            int index = -1;
            double x = 0, y = 0;
            int count = 0;
            while (it.hasNext()) {
              Tuple2<Integer, Point> p = it.next();
              x += p.f1.x;
              y += p.f1.y;
              index = p.f0;
              count++;
            }
            collector.collect(new Centroid(index, x / count, y / count));
          }
        });

    // feed new centroids back into next iteration
    DataSet<Centroid> finalCentroids = loop.closeWith(newCentroids);

    DataSet<Tuple2<Integer, Point>> clusteredPoints = points
        // assign points to final clusters
        .map(new KMeans.SelectNearestCenter()).withBroadcastSet(finalCentroids, "centroids");

    // emit result
    if (params.has("output")) {
      clusteredPoints.writeAsCsv(params.get("output"), "\n", " ");

      // since file sinks are lazy, we trigger the execution explicitly
      env.execute("KMeans Example");
    } else {
      System.out.println("Printing result to stdout. Use --output to specify output path.");
      clusteredPoints.print();
    }
  }

  // *************************************************************************
  //     DATA SOURCE READING (POINTS AND CENTROIDS)
  // *************************************************************************

  private static DataSet<Centroid> getCentroidDataSet(ParameterTool params, ExecutionEnvironment env) {
    DataSet<Centroid> centroids;
    if (params.has("centroids")) {
      centroids = env.readCsvFile(params.get("centroids"))
          .fieldDelimiter(" ")
          .pojoType(Centroid.class, "id", "x", "y").setParallelism(params.getInt("parallel", 1));;
    } else {
      System.out.println("Executing K-Means example with default centroid data set.");
      System.out.println("Use --centroids to specify file input.");
      centroids = KMeansData.getDefaultCentroidDataSet(env);
    }
    return centroids;
  }

  private static DataSet<Point> getPointDataSet(ParameterTool params, ExecutionEnvironment env) {
    DataSet<Point> points;
    if (params.has("points")) {
      // read points from CSV file
      points = env.readCsvFile(params.get("points"))
          .fieldDelimiter(" ")
          .pojoType(Point.class, "x", "y").setParallelism(params.getInt("parallel", 1));
    } else {
      System.out.println("Executing K-Means example with default point data set.");
      System.out.println("Use --points to specify file input.");
      points = KMeansData.getDefaultPointDataSet(env);
    }
    return points;
  }

  // *************************************************************************
  //     DATA TYPES
  // *************************************************************************

  // *************************************************************************
  //     USER FUNCTIONS
  // *************************************************************************

  /** Determines the closest cluster center for a data point. */
  @FunctionAnnotation.ForwardedFields("*->1")
  public static final class SelectNearestCenter extends RichMapFunction<Point, Tuple2<Integer, Point>> {
    private Collection<Centroid> centroids;

    /** Reads the centroid values from a broadcast variable into a collection. */
    @Override
    public void open(Configuration parameters) throws Exception {
      this.centroids = getRuntimeContext().getBroadcastVariable("centroids");
    }

    @Override
    public Tuple2<Integer, Point> map(Point p) throws Exception {

      double minDistance = Double.MAX_VALUE;
      int closestCentroidId = -1;

      // check all cluster centers
      for (Centroid centroid : centroids) {
        // compute distance
        double distance = p.euclideanDistance(centroid);

        // update nearest cluster if necessary
        if (distance < minDistance) {
          minDistance = distance;
          closestCentroidId = centroid.id;
        }
      }



      // emit a new record with the center id and the data point.
      return new Tuple2<>(closestCentroidId, p);
    }
  }
}
