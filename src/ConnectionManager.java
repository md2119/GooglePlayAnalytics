import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.Vector;

import org.apache.log4j.Logger;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public class ConnectionManager
{
	public enum Mode {NO_PROXIES, INCREASE_WAIT_ON_BLOCK, DECREASE_WAIT_OVER_TIME, ROTATE_PROXIES, NORMAL};
	private final String webCacheUrl = "http://webcache.googleusercontent.com";
	private Vector<String> proxies = new Vector<String>();
	private Mode mode;
	private Logger logger;
	private String userAgent = "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11";
	private Date check = new Date();
	private int proxySelect = 0;
	private int timeout = 20000;
	private long waitTime;
	private long minWaitTime = 500;
	private long adjustmentInterval = 1000 * 60 * 60 * 24;
	private long waitTimeAdjustmentFactor = 2;
	private boolean webcache = false;

	public ConnectionManager()
	{
		setup(null, null, null, Mode.NO_PROXIES, minWaitTime, null, false);
	}

	public ConnectionManager(Connection connection, String database, String source, boolean webcache)
	{
		setup(connection, database, source, Mode.NORMAL, minWaitTime, null, webcache);
	}

	public ConnectionManager(Connection connection, String database, String source, Mode mode, long waitTime, Logger logger, boolean webcache)
	{
		setup(connection, database, source, mode, waitTime, logger, webcache);
	}

	public ConnectionManager(Vector<String> proxies, String source, boolean webcache)
	{
		this.proxies = proxies;

		setup(null, null, source, Mode.NORMAL, minWaitTime, null, webcache);
	}

	public ConnectionManager(Vector<String> proxies, String source, Mode mode, long waitTime, Logger logger, boolean webcache)
	{
		this.proxies = proxies;
		setup(null, null, source, mode, waitTime, logger, webcache);
	}

	private void setup(Connection connection, String database, String source, Mode mode, long waitTime, Logger logger, boolean webcache)
	{
		this.mode = mode;
		this.waitTime = waitTime > minWaitTime ? waitTime : minWaitTime;
		this.logger = logger;
		this.webcache = webcache;

		if(mode.equals(Mode.NO_PROXIES))
		{
			return;
		}

		setPort(3128);
		loadProxies(connection, database, source);

		if(this.mode.equals(Mode.ROTATE_PROXIES))
		{
			proxySelect = -1;
			this.waitTime /= proxies.size();
		}
	}

	private void loadProxies(Connection connection, String database, String source)
	{
		if(connection == null || database == null || database.equals(""))
		{
			return;
		}

		try
		{
			Document document = null;
			Statement statement = connection.createStatement();
			ResultSet resultSet = statement.executeQuery("SELECT proxyaddr FROM " + database + ".proxies where proxyaddr like ('%:3128')");

			while(resultSet.next())
			{
				String[] ptmp = resultSet.getString("proxyaddr").split(":");
				proxies.add(ptmp[0]); //resultSet.getString("proxyaddr"));
				System.setProperty("http.proxyHost", proxies.lastElement());

				try
				{
					document = null;
					document = Jsoup.connect(source).userAgent(userAgent).timeout(timeout).get();
				}
				catch(IOException e)
				{
					log("Proxy " + proxies.lastElement() + " was unable to connect to " + source + ".", true);
				}

				if(document == null)
				{	
					proxies.remove(proxies.size() - 1);
				}
			}

			resultSet.close();
			statement.close();
		}
		catch(SQLException e)
		{
			log("Received MySQL error " + e.getErrorCode() + ": " + e.getMessage(), true);
		}

		if(proxies.size() == 0)
		{
			log("No valid proxies found in table " + database + ".proxies.", true);

			mode = Mode.NO_PROXIES;
		}
	}

	public void setAdjustmentInterval(long millis)
	{
		adjustmentInterval = millis > 0 ? millis : adjustmentInterval;
	}

	public void setPort(int port)
	{
		System.setProperty("http.proxyPort", Integer.toString(port));
	}

	public void setTimeout(int millis)
	{
		timeout = millis < 0 ? 0 : millis;
	}

	public void setUserAgent(String userAgent)
	{
		this.userAgent = userAgent;
	}

	public void setWaitTimeAdjustmentFactor(long waitTimeAdjustmentFactor)
	{
		this.waitTimeAdjustmentFactor = waitTimeAdjustmentFactor > 0 ? waitTimeAdjustmentFactor : this.waitTimeAdjustmentFactor;
	}

	private void log(String message, boolean warn)
	{
		if(logger == null)
		{
			System.out.println(message);
		}
		else if(warn)
		{
			logger.warn(message);
		}
		else
		{
			logger.info(message);
		}
	}

	private String getProxy()
	{
		proxySelect = mode.equals(Mode.ROTATE_PROXIES) ? proxySelect + 1 : proxySelect;

		String proxy = proxies.get(proxySelect >= proxies.size() ? proxySelect = 0 : proxySelect);

		if(mode.equals(Mode.DECREASE_WAIT_OVER_TIME) && waitTime > minWaitTime)
		{
			Date current = new Date();
			long timeSinceLastAdjustment = current.getTime() - check.getTime();

			if(timeSinceLastAdjustment >= adjustmentInterval)
			{
				long newWaitTime = waitTime / waitTimeAdjustmentFactor;

				waitTime = newWaitTime < minWaitTime ? minWaitTime : newWaitTime;
				check = current;

				log("Wait time lowered to " + waitTime / 1000 + " second" + (waitTime == 1000 ? "" : "s") + ".", false);
			}
		}

		return proxy;
	}

	private void proxyBlocked(String url)
	{
		log("Blocked while connecting to " + url + ".", true);

		switch(mode)
		{
		case INCREASE_WAIT_ON_BLOCK:
			waitTime *= waitTimeAdjustmentFactor;
			break;
		case ROTATE_PROXIES:
			waitTime *= proxies.size() * waitTimeAdjustmentFactor;
			mode = Mode.INCREASE_WAIT_ON_BLOCK;

			log("Changed mode to Increase Wait on Block.", false);
			break;
		case DECREASE_WAIT_OVER_TIME:
			waitTime *= waitTimeAdjustmentFactor;
			mode = Mode.INCREASE_WAIT_ON_BLOCK;

			log("Changed mode to Increase Wait on Block.", false);
			break;
		case NO_PROXIES:
			System.exit(0);
			break;
		default:
			break;
		}

		proxies.remove(proxySelect);

		if(proxies.size() == 0)
		{
			log("All proxies have been blocked.", true);
			System.exit(0);
		}

		log("Wait time raised to " + waitTime / 1000 + " second" + (waitTime == 1000 ? "" : "s") + ".", false);
	}

	public Document connect(String url, int attempts)
	{
		if(!webcache)
		{
			return connectPrimary(url, attempts);
		}

		Document document = connectPrimary(webCacheUrl + "/search?q=cache:" + url, attempts);

		return document == null ? connectPrimary(url, attempts) : document;
	}

	public Document connect(String url)
	{

		return connect(url, 3);
	}

	public Document connectDirectly(String url, int attempts)
	{
		return connectPrimary(url, attempts);
	}

	public Document connectDirectly(String url)
	{
		return connectPrimary(url, 3);
	}

	private Document connectPrimary(String url, int attempts)
	{
		Document document = null;

		if(!mode.equals(Mode.NO_PROXIES))
		{
			System.setProperty("http.proxyHost", getProxy());
		}

		for(int i = 0; i < attempts; i++)
		{
		
			try
			{
				document = Jsoup.connect(url).userAgent(userAgent).timeout(timeout).get();

				sleep(waitTime + (long) (Math.random() * 1000.0));
				//sleep(waitTime - 500 + (long) (Math.random() * 1000.0));
				break;
			}
			catch(HttpStatusException e)
			{
				if(!url.startsWith(webCacheUrl) && e.getStatusCode() == 403)
				{
					proxyBlocked(url);
					return connectPrimary(url, attempts);
				}
				else if(!url.startsWith(webCacheUrl))
				{
					log("Received HTTP error status " + e.getStatusCode() + " from " + url + ".", false);
				}

				return null;
			}
			catch(IOException e)
			{
				if(attempts == 1)
				{
					continue;
				}

				long failWaitTime = 1;

				log("Failed attempt " + (i + 1) + " of " + attempts + " to connect to '" + url + "'." + (i + 1 < attempts ? " Waiting "
						+ failWaitTime + (failWaitTime > 1 ? " minutes" : " minute") + " to retry connection." : ""), true);

				if(i + 1 < attempts)
				{
					sleep(failWaitTime * 1000 * 60);
				}
			}
		}

		return document;
	}

	private void sleep(long millis)
	{
		try
		{
			Thread.sleep(millis);
		}
		catch(InterruptedException e)
		{
			log("Sleep interrupted.", true);
		}
	}
}

