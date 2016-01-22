package util;

import static org.openqa.selenium.support.ui.ExpectedConditions.not;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Wait;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.util.concurrent.TimeUnit;

/**
 * @author fhaertig
 * @author Jan Wolter
 * @since 21.01.16
 */
public class WebDriver3ds extends HtmlUnitDriver {

    private static final int DEFAULT_TIMEOUT = 5;

    public WebDriver3ds() {
        super(BrowserVersion.FIREFOX_38, true);

        final Timeouts timeouts = manage().timeouts();
        timeouts.implicitlyWait(DEFAULT_TIMEOUT, TimeUnit.SECONDS);
        timeouts.pageLoadTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS);
        timeouts.setScriptTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS);

        final WebClient webClient = getWebClient();
        webClient.setJavaScriptTimeout(2000);
        webClient.getOptions().setThrowExceptionOnScriptError(false);
        webClient.getOptions().setPopupBlockerEnabled(true);

        webClient.setIncorrectnessListener((message, origin) -> {
            //swallow these messages
        });
        webClient.setCssErrorHandler(new SilentCssErrorHandler());
    }

    /**
     * Submits the given {@code password} at the given {@code url}'s "password" element, waits for a redirect and
     * returns the URL it was redirected to.
     *
     * @param url the URL to navigate to
     * @param password the password
     * @return the URL the browser was redirected to after submitting the {@code password}
     */
    public String execute3dsRedirectWithPassword(final String url, final String password) {
        navigate().to(url);

        final WebElement element = findElement(By.xpath("//input[@name=\"password\"]"));
        element.sendKeys(password);
        element.submit();

        // Wait for redirect to complete
        final Wait<WebDriver> wait = new WebDriverWait(this, 10);
        wait.until(not(ExpectedConditions.urlContains("3ds")));
        return getCurrentUrl();
    }

    public void quit() {
        super.quit();
    }
}
