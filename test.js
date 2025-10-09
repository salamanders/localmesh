const puppeteer = require('puppeteer');

(async () => {
    let browser;
    try {
        console.log('Launching browser...');
        browser = await puppeteer.launch({
            headless: true,
            args: [
                '--no-sandbox',
                '--disable-setuid-sandbox',
                '--disable-dev-shm-usage',
                '--disable-accelerated-2d-canvas',
                '--no-first-run',
                '--no-zygote',
                '--single-process',
                '--disable-gpu'
            ]
        });

        const page = await browser.newPage();
        let errorDetected = false;

        page.on('pageerror', error => {
            console.error(`Page error detected: ${error.message}`);
            errorDetected = true;
        });

        console.log('Navigating to http://localhost:8000/index.html...');
        await page.goto('http://localhost:8000/index.html', { waitUntil: 'networkidle0' });

        // Wait for a bit to see if any async errors pop up
        await new Promise(resolve => setTimeout(resolve, 5000));

        if (errorDetected) {
            console.error('Test Failed: JavaScript errors were found on the page.');
            process.exit(1);
        } else {
            console.log('Test Passed: No JavaScript errors detected.');
            process.exit(0);
        }

    } catch (error) {
        console.error(`An error occurred during the test: ${error}`);
        process.exit(1);
    } finally {
        if (browser) {
            await browser.close();
        }
    }
})();