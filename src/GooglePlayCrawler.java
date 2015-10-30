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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;


public class GooglePlayCrawler implements Runnable{

	int timeout = 20000;
	static String userAgent = "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11";
	String seed = "";
	public static String APP_LINKS = "div.details > a.card-click-target[tabindex=-1][aria-hidden=true]";
	String output_directory = "";
	String logfile = "";
	String homeURL = "https://play.google.com";
	int cycle = 1, seedID = 1;
	
	private Map<String, Boolean> urlqueue = new ConcurrentHashMap<String, Boolean>();
	
	public GooglePlayCrawler() {}
	
	public GooglePlayCrawler(String seed) {
		this.seed = seed;
		createDir("googleplay");
		this.logfile = output_directory + "/logfile";
	}
	
	public void run() {
		initialRequest();
	}
	
	public void initialRequest() {
		try{

			Document doc = Jsoup.connect(seed).userAgent(userAgent).timeout(timeout).get();
			
			Elements nodes = doc.select(APP_LINKS);

			if(nodes != null) {
			
				for(Element node : nodes) {

					if(node.attr("href") != null && node.attr("href").contains("details?id=") ) {
						String url = node.attr("href");
						urlqueue.put(url, false);
					}
				}
				getAppPage(urlqueue);
			}
			
			int baseSkip = 60;
			int currmultiplier = 1;
			int errcount = 0;
			boolean isDonePagging = false;
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
					
						if(node.attr("href") != null && node.attr("href").contains("details?id=") ) {
							String url = node.attr("href");
							if(urlqueue.containsKey(url)) 
								errcount++; //isDonePagging = true;
							urlqueue.put(url, false);
							
						}
					}
					getAppPage(urlqueue);
				}
				currmultiplier++;
				//start={0}&num={1}&numChildren=0&ipf=1&xhr=1
			} while(errcount < 1000 || currmultiplier < 10);
			
		} catch(Exception e) {
			e.printStackTrace();
			return;

		}
	
	}
	
	public void getAppPage(Map<String, Boolean> urlqueue) {
		Connection conn = null;
		try{
			conn = DriverManager.getConnection( "jdbc:mysql://localhost/appstore?user=root&password=" );
			for(Map.Entry<String, Boolean> entry: urlqueue.entrySet()) {
				String url = entry.getKey();
				
				Document doc = Jsoup.connect(homeURL+url).userAgent(userAgent).timeout(timeout).get();
				PreparedStatement insertpage = conn.prepareStatement("INSERT INTO appraw(seed_id, app_url, app_page, crawltime, cycle) values (?,?,?,?,?)");
				insertpage.setInt(1, seedID);
				insertpage.setString(2, url);
				insertpage.setString(3, doc.toString());
				insertpage.setTimestamp(4, new java.sql.Timestamp(Calendar.getInstance().getTime().getTime()));
				insertpage.setInt(5, cycle);
				insertpage.executeUpdate();
				insertpage.close();
							
				System.out.println(url);

				String ouputfilename = getTime() + "_" + url.substring(url.indexOf("id=")+3);
				BufferedWriter htmlfilewrite = new BufferedWriter(new FileWriter(output_directory+"/" + ouputfilename + ".html", true));
				htmlfilewrite.append( doc.toString() );
				htmlfilewrite.close();
				
				writeLog(url, logfile);
				
				urlqueue.remove(url);
			}
			conn.close();

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
		
		String seed = "https://play.google.com/store/apps/collection/topselling_free";
		final int NTHREDS = 1; //Integer.parseInt(args[0]);

		try {
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			ExecutorService executor = Executors.newFixedThreadPool(NTHREDS);
			executor.execute(  new GooglePlayCrawler( seed ) );
			executor.shutdown();

		} 
		catch(Exception ee) {
			ee.printStackTrace();
			System.exit(1);

		}

	}

}
