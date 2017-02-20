import com.mongodb.spark.MongoSpark;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.ForeachFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.bson.Document;
import org.codehaus.janino.Java;

import javax.print.Doc;
import java.util.Arrays;
import static java.util.Arrays.asList;

public class Main {
    public static void main(String[] args) {
        String response = ApiConnector.executeGet("https://newsapi.org/v1/articles?source=techcrunch&apikey=919447fbe85f4ad09e7ee0b868efdbd4");

        // System.out.println(response);

        SparkSession spark = SparkSession.builder()
                .master("local")
                .appName("News Article Recommendations")
                .config("spark.mongodb.input.uri", "mongodb://172.17.0.2/test.myCollection")
                .config("spark.mongodb.output.uri", "mongodb://172.17.0.2/test.myCollection")
                .getOrCreate();

        JavaRDD<String> anotherPeopleRDD =
                new JavaSparkContext(spark.sparkContext()).parallelize(Arrays.asList(response));
        Dataset<Row> df = spark.read().json(anotherPeopleRDD);

        //Dataset<Row> df = spark.read().json("src/main/resources/articles.json");
        //df.printSchema();
        //df.show();

        Dataset<Row> articles = df.select(org.apache.spark.sql.functions.explode(df.col("articles")).as("a"));
        //articles.printSchema();
        articles.select("a.author", "a.title", "a.description", "a.publishedAt").show();

        /* articles.foreach(new ForeachFunction<Row>() {
            @Override
            public void call(Row row) throws Exception {
                Row realrow = (Row) row.get(0); // for some reason nested?
                String author = realrow.getString(0);
                String title = realrow.getString(1);
                String description = realrow.getString(2);
                String publishedAt = realrow.getString(3);


            }
        }); */

        JavaSparkContext jsc = new JavaSparkContext(spark.sparkContext());

        MongoSpark.write(articles).option("collection", "articles").mode("overwrite").save();

        jsc.close();
        spark.stop();
    }
}