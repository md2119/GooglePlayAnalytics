/**
 * Google Play Store Crawler
 * @Author	Mandar Darwatkar
 * @Institution University of California, Riverside
 * @Date	10/29/2015
 * @Input	cycle		args[0] - crawl cycle
 * 			seedFile	args[1] - File containing seed to be crawled
 * 			username	args[2] - Database username
 * 			password	args[3] - Database password
 * 
 * @Output	
 */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
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
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;


public class GooglePlayCrawler1 implements Runnable{

	String dbUserName = "";
	String dbPassword = "";
	String output_directory = null;
	String logfile = null;
	String homeURL = "https://play.google.com";
	String seed = null;
	int timeout = 20000;
	static String userAgent = "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11";
	
	/*
	 * Pattern to get app links from seed page
	 */
	public static String APP_LINKS = "div.details > a.card-click-target[tabindex=-1][aria-hidden=true]";
	int cycle = 0;
	int seedID = 0;
	
	public GooglePlayCrawler1() {}
	
	public GooglePlayCrawler1(int cycle, int seedID, String seed, String dbUserName, String dbPassword) {
		
		this.cycle = cycle;
		this.seedID = seedID;
		this.seed = seed;
		this.dbUserName = dbUserName;
		if(dbPassword.equals("n/a"))
			this.dbPassword = "";
		else
			this.dbPassword = dbPassword;
		createDir("crawledhtmlfiles");
		this.logfile = output_directory + "/logfile";
	}
	
	public void run() {
		initialRequest();
	}
	
	/**
	 * Crawl the seed URL to get links to app page
	 */
	public void initialRequest() {
		Connection conn = null;
		try{
			
			conn = DriverManager.getConnection( "jdbc:mysql://localhost/appstore?user=" + this.dbUserName + "&password=" + this.dbPassword + "" );
			PreparedStatement insertURL = null;
			/*
			 * First send a get request
			 */
			Document doc = Jsoup.connect(seed).userAgent(userAgent).timeout(timeout).get();
		
			Elements nodes = doc.select(APP_LINKS);

			/*
			 * If apps exists
			 */
			if(nodes != null) {
			
				for(Element node : nodes) {

					/*
					 * Insert each app URL into database to be crawled later
					 */
					  if(node.attr("href") != null && node.attr("href").contains("details?id=") ) {
					 
						String url = node.attr("href");
						
						insertURL = conn.prepareStatement("INSERT IGNORE INTO appraw(seed_id, app_url, appid, cycle) values (?,?,?,?)");
						insertURL.setInt(1, seedID);
						insertURL.setString(2, url);
						insertURL.setString(3, url.split("=")[1].trim());
						insertURL.setInt(4, cycle);
						insertURL.executeUpdate();
						insertURL.close();

					}
				}
				
				/*
				 * After every batch of getting app URLs, crawl the app pages
				 */
				getAppPage(conn);
			}
			
			/*
			 * Since loading next apps is handled through POST request, we cannot use GET.
			 * When end of page is hit, next next apps are dynamically loaded.
			 * URL does not change, hence POST method.
			 */
			int baseSkip = 60;
			int currmultiplier = 1;
			int errcount = 0;
			boolean isDonePagging = false;
			
			/*
			 * Send POST requests to next apps, limited by 100 POST requests.
			 * Thus, we can get 6000 apps.
			 * TODO:: Need to be more robust. 
			 */
			
			do {
				doc = Jsoup.connect(seed)
						.data("start", Integer.toString(currmultiplier * baseSkip) )
						.data("num", Integer.toString(baseSkip) )
						.data("numChildren","0")
						.data("ipf","1")
						.data("xhr","1")
						.userAgent(userAgent)
						.post();
				
				nodes = doc.select(APP_LINKS);

				if(nodes != null) {
				
					for(Element node : nodes) {

						/*
						 * Insert each app URL into database to be crawled later
						 */			
						if(node.attr("href") != null && node.attr("href").contains("details?id=") ) {
							String url = node.attr("href");

							insertURL = conn.prepareStatement("INSERT IGNORE INTO appraw(seed_id, app_url, appid, cycle) values (?,?,?,?)");
							insertURL.setInt(1, seedID);
							insertURL.setString(2, url);
							insertURL.setString(3, url.split("=")[1].trim());
							insertURL.setInt(4, cycle);
							insertURL.executeUpdate();
							insertURL.close();
							
						}
					}
					
					/*
					 * After every batch of getting app URLs, crawl the app pages
					 */
					getAppPage(conn);
				}
				currmultiplier++;
				//start={0}&num={1}&numChildren=0&ipf=1&xhr=1
			} while(errcount < 1000 || currmultiplier < 100);
			
	
		} catch(Exception e) {
			e.printStackTrace();
			return;

		} finally {
			try {
				conn.close();
			} catch(SQLException e) {
				e.printStackTrace();
			}			
		}

	}
	
	/**
	 * Crawl the actual app page by retrieving uncrawled links from database
	 * @param conn 		Database Connection
	 */
	public void getAppPage(Connection conn) {
	
		try{
			
			/*
			 * Get all uncrawled app links
			 */
			Statement smt = conn.createStatement();
			ResultSet rs_url = smt.executeQuery( "SELECT app_url FROM appstore.appraw where year(crawltime)='0000'" );
			
			while(rs_url.next()) {
				
				String url = rs_url.getString("app_url");
				
				/*
				 * Since viewing permission is a dynamic event, it cannot be handled through simple GET/POST.
				 * Therefore, Selenium library is used to simulate user events
				 */
				WebDriver driver = new FirefoxDriver();
				JavascriptExecutor js = (JavascriptExecutor) driver;
				
				/*
				 * Open the app page in browser
				 */
				driver.get(homeURL + url);
				/*
				 * Wait till the page is loaded completely.
				 * This is to ensure that all javascripts are loaded and ready/running
				 */
				driver.manage().timeouts().pageLoadTimeout(10, TimeUnit.SECONDS);
				
				/*
				 * Find the button to be clicked
				 */
				WebElement permissionButton = driver.findElement(By.className("id-view-permissions-details"));
				while(!permissionButton.isEnabled()) {
					Thread.sleep(100);
				}
				/*
				 * Click the 'View details' button to open Permission window
				 */
				permissionButton.click();
					//Object buttonObject = js.executeScript("var elements = document.getElementsByTagName('button'); var len = elements.length; while(len--) {if(elements[len].className == 'content id-view-permissions-details fake-link'){ elements[len].click();}}");
					//Thread.sleep(10000);
				
				/*
				 * Wait until permission window is opened
				 */
				WebDriverWait wait = new WebDriverWait(driver, 10);
				wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("purchase-details")));
				
				/*
				 * Since pagesource does not contain dynamically added code, we use javascript to get HTML body
				 */
				Object docObject = js.executeScript("return document.getElementsByTagName('html')[0].innerHTML");
				String doc = docObject.toString();
				
				driver.close();
				driver.quit();
				
				PreparedStatement insertpage = conn.prepareStatement("UPDATE appraw set app_page = ?, crawltime = ? WHERE app_url=?");
				insertpage.setString(1, doc);
				insertpage.setTimestamp(2, new java.sql.Timestamp(Calendar.getInstance().getTime().getTime()));
				insertpage.setString(3, url);
				insertpage.executeUpdate();
				insertpage.close();
				
				
				String ouputfilename = getTime() + "_" + url.substring(url.indexOf("id=")+3);
				BufferedWriter htmlfilewrite = new BufferedWriter(new FileWriter(output_directory+"/" + ouputfilename + ".html", true));
				htmlfilewrite.append( doc );
				htmlfilewrite.close();
				
				writeLog(new Date() + " :: " + url, logfile);
				
			}
			smt.close();
			
			return;
			
		} catch(Exception e) {
			e.printStackTrace();
			getAppPage(conn);
			
			return;

		}

	}
	
	/**
	 * Create directory to store output files and log files
	 * @param output_directory		output directory name
	 */
	public void createDir(String output_directory) {

		this.output_directory = output_directory;
		File file = new File(output_directory);
		if ( !file.exists() ) {
			file.mkdir();
		}

	}

	/**
	 * Write to Log file
	 * @param logstr		log string
	 * @param logfilepath	log file path
	 */
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
	
	/**
	 * Get current time in specified format
	 * @return		current time
	 */
	public String getTime() {

		Date date = new Date();
		DateFormat dateFormat = null;
		dateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm");
		return dateFormat.format(date);
	}

	public static void main(String[] args) {
			
		int cycle = Integer.parseInt(args[0]);
		String seedFile = args[1];
		String username = args[2];
		String password = args[3];
		//String chromeDriverPath = args[4];
		
		final int NTHREDS = 1;
		
		try {
			
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			ExecutorService executor = Executors.newFixedThreadPool(NTHREDS);
			
			BufferedReader readSeed = new BufferedReader(new FileReader(seedFile));		
			String line;			
			
			while((line = readSeed.readLine()) != null) {		
				
				executor.execute(  new GooglePlayCrawler1( cycle, Integer.parseInt(line.split(" ")[0]), line.split(" ")[1], username, password ) );					
			}
			
			readSeed.close();
			executor.shutdown();

		} 
		catch(Exception ee) {
			
			ee.printStackTrace();
			System.exit(1);
		}

	}

}
