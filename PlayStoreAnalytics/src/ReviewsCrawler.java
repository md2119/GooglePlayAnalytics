import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;


public class ReviewsCrawler implements Runnable{

	int timeout = 20000;
	static String userAgent = "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11";
	String seed = "";
	String output_directory = "";
	String logfile = "";
	String homeURL = "https://play.google.com";
	int cycle = 2, seedID = 1;
	
	//private Map<String, Boolean> urlqueue = new ConcurrentHashMap<String, Boolean>();
	
	public ReviewsCrawler() {}
	
	public ReviewsCrawler(String seed) {
		this.seed = seed;
		createDir("crawledreviewsfiles");
		this.logfile = output_directory + "/logfile";
	}
	
	public void run() {
		getAppPage();
	}
	
	public void getAppPage() {
		Connection conn = null;
		try{
			
			conn = DriverManager.getConnection( "jdbc:mysql://localhost/appstore?user=root&password=" );
			
			Statement smt = conn.createStatement();
			ResultSet rs_url = smt.executeQuery( "SELECT app_url FROM appstore.appraw where review != 1" );
			
			while(rs_url.next()) {
				
				String url = rs_url.getString("app_url");
			
				WebDriver driver = new ChromeDriver();
				JavascriptExecutor js = (JavascriptExecutor) driver;
				
				driver.get(homeURL + url);
				driver.manage().timeouts().pageLoadTimeout(10, TimeUnit.SECONDS);
				
				WebElement expandButton = driver.findElement(By.className("expand-next"));
				while(!expandButton.isEnabled()) {
					Thread.sleep(100);
				}
				
				expandButton.click();
					//Object buttonObject = js.executeScript("var elements = document.getElementsByTagName('button'); var len = elements.length; while(len--) {if(elements[len].className == 'content id-view-permissions-details fake-link'){ elements[len].click();}}");
					//Thread.sleep(10000);
				WebDriverWait wait = new WebDriverWait(driver, 10);
				wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("expand-pages-container")));
			
				Object docObject = js.executeScript("return document.getElementsByTagName('html')[0].innerHTML");
				String doc = docObject.toString();
				
				driver.close();
				driver.quit();
				
					//Document doc = Jsoup.connect(homeURL+url).userAgent(userAgent).timeout(timeout).get();
				PreparedStatement insertpage = conn.prepareStatement("INSERT appreviews(appid, reviewid, review) values (?, ?, ?)");
				insertpage.setString(1, url.split("=")[1].trim());
				insertpage.setTimestamp(2,);
				insertpage.setString(3,);
				insertpage.executeUpdate();
				insertpage.close();
				
				System.out.println(url);

				String ouputfilename = getTime() + "_" + url.substring(url.indexOf("id=")+3);
				BufferedWriter htmlfilewrite = new BufferedWriter(new FileWriter(output_directory+"/" + ouputfilename + ".html", true));
				htmlfilewrite.append( doc );
				htmlfilewrite.close();
				
				writeLog(new Date() + " :: " + url, logfile);
				
			}
			smt.close();
			conn.close();
			
			return;
			
		} catch(Exception e) {
	
			e.printStackTrace();
			return;

		}

	}
	
	public void createDir(String output_directory) {

		this.output_directory = output_directory;
		File file = new File(output_directory);
		if ( !file.exists() ) {
			file.mkdir();
		}

	}

	public void writeLog(String logstr, String logfilepath) {

		BufferedWriter log;

		try {
			log = new BufferedWriter(new FileWriter(logfilepath, true));
			log.append(logstr);
			log.newLine();
			log.close();

		} catch (IOException ioe) {

			System.out.println("!!!!!an IOException happened while writing to log file");
			ioe.printStackTrace();
			return;
		}

	}
	
	public String getTime() {

		Date date = new Date();
		DateFormat dateFormat = null;
		dateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm");
		return dateFormat.format(date);
	}

	public static void main(String[] args) {
		System.setProperty("webdriver.chrome.driver", "E:\\workspace\\PlayStoreAnalytics\\chromedriver_win32\\chromedriver.exe");
		
		String seed = "https://play.google.com/store/apps/collection/topselling_free";
		final int NTHREDS = 1; //Integer.parseInt(args[0]);

		
		try {
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			ExecutorService executor = Executors.newFixedThreadPool(NTHREDS);
			executor.execute(  new ReviewsCrawler( seed ) );
			executor.shutdown();

		} 
		catch(Exception ee) {
			ee.printStackTrace();
			System.exit(1);

		}

	}

}
