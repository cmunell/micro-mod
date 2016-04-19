package edu.cmu.ml.rtw.micro.mod;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

import edu.cmu.ml.rtw.util.files.FileUtility;  // TODO: can/should we detach from this in order to eliminate dependency on OntologyLearner svn?

import edu.cmu.ml.rtw.generic.data.DataTools;
import edu.cmu.ml.rtw.generic.data.annotation.nlp.AnnotationTypeNLP;
import edu.cmu.ml.rtw.generic.data.annotation.nlp.DocumentNLP;
import edu.cmu.ml.rtw.generic.data.annotation.nlp.DocumentNLPInMemory;
import edu.cmu.ml.rtw.generic.data.annotation.nlp.DocumentNLPMutable;
import edu.cmu.ml.rtw.generic.data.annotation.nlp.Language;
import edu.cmu.ml.rtw.generic.data.annotation.nlp.SerializerDocumentNLPHTML;
import edu.cmu.ml.rtw.generic.data.annotation.nlp.SerializerDocumentNLPMicro;
import edu.cmu.ml.rtw.generic.data.annotation.nlp.micro.Annotation;
import edu.cmu.ml.rtw.generic.model.annotator.nlp.PipelineNLP;
import edu.cmu.ml.rtw.generic.model.annotator.nlp.PipelineNLPStanford;
import edu.cmu.ml.rtw.generic.util.OutputWriter;
import edu.cmu.ml.rtw.generic.util.ThreadMapper;
import edu.cmu.ml.rtw.micro.data.MicroDataTools;
import edu.cmu.ml.rtw.micro.model.annotation.nlp.PipelineNLPMicro;

/**
 * Servelet that provides access to Microreading-on-Demand based on our 2015 JSON-format internal
 * annotation scheme.
 *
 * This has been developed for use in Tomcat 6
 *
 * "JSON2015" is not official terminology as of this writing, but it seems appropriate to adopt some
 * way to, even if ultimately provisional, to distinguish this from some future thing that might
 * have reason to name itself "JSON" something or other.
 *
 * Our github-centric stuff does not yet have any standardization or coordination for file-based
 * prarameter settings, but we need to get this up and running, as ever, in a day for an important
 * deadline, so we just hardcode for now under the blithe hope that it won't just help cement in an
 * obvious mess.
 */
public class JSON2015Servlet extends HttpServlet {
    private final static Logger log = Logger.getLogger(HttpServlet.class);
    protected String responseLog = null;
    protected static Object responseLogMutex = new Object();

    protected PipelineNLPMicro microPipeline;
    protected PipelineNLPStanford stanfordPipeline;

    private static int maxAnnotationSentenceLength; 

    public void init() {
        List<PipelineNLPMicro.Annotator> disabled = new ArrayList<PipelineNLPMicro.Annotator>();
        //disabled.add(PipelineNLPMicro.Annotator.HDP_PARSER);
        microPipeline = new PipelineNLPMicro(0.9, disabled);
        log.info("PipelineNLPMicro instantiated");
        stanfordPipeline = new PipelineNLPStanford(maxAnnotationSentenceLength);

        // responseLog = properties.getProperty("responseLog", null);
        responseLog = "/nell/results/ongoing-output/MoD-JSON2015Servlet.log";
        maxAnnotationSentenceLength = 30;
    }

    /**
     * Renames the existing log and compresses it
     *
     * In case of error, logs about the error and presses on.
     */
    protected synchronized void rotateResponseLog() {
        try {
            File srcFile = new File(responseLog);
            if (!srcFile.exists()) return;
            log.info("Rotating " + responseLog + "...");
            String rotatedName = responseLog + (new SimpleDateFormat("yyyy-MM-dd").format(new Date()));

            // It's not impossible to want to rotate the log more than once per day, so add sequence
            // numbers after that.  This is better failing and logging about the failure on each
            // requests (and then winding up with a huge log), and better than overwriting an existing
            // log.
            String sequence = "";
            int cnt = 0;
            File dstFile;
            while (true) {
                String rotatedCompressedName = rotatedName + sequence + ".gz";
                dstFile = new File(rotatedCompressedName);
                if (!dstFile.exists()) break;
                cnt++;
                sequence = "." + sequence;
            }
        
            if (!srcFile.renameTo(dstFile))
                throw new RuntimeException("Failed to rename " + srcFile + " to " + dstFile);
        
            // Compression might take some time, so spawn a thread to do that for us rather than sit
            // here in a synchronized method holding everything up.  The 1 means to tolerate failure
            // after logging about it.
            FileUtility.gzipInThread(srcFile.toString(), dstFile.toString(), true, 1);
        } catch (Exception e) {
            log.error("Uncaught exception during log rotation", e);
        }
    }

    /**
     * Rotates the log if we think it's time to do so
     */
    protected synchronized void rotateResponseLogIfNecessary() {
        // Let's try rotating it every 500MB.  The main idea is to make it more friendly to grep,
        // less, etc.
        File logFile = new File(responseLog);
        if (logFile.length() > 500L * 1024L * 1024L)
            rotateResponseLog();
    }

    protected void logResponse(String plaintextDocument, String rawJSON, String hostname) {
        if (responseLog == null) return;
        
        StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date()));
        sb.append("\t");
        sb.append(hostname);

        // How best to log the input document is an open question.  We'd need to do something
        // fancier than a TSV file to log the actual document in a general fashion, since,
        // presumably, we might want to support al sorts of document formats that would need a bunch
        // of inconvenient escaping to shoehorn in there.  It's not entirely clear if there's a
        // categorically better approach than TSV to this, and niether is it clear that it will be
        // tennable or desirable to be logging the entire input document and response, anyway.
        sb.append(plaintextDocument.replace('\t', ' ').replace('\n', ' '));

        sb.append(rawJSON.replace('\t', ' ').replace('\n', ' '));

        String line = sb.toString();
        synchronized (responseLogMutex) {
            // This will probably be a bottleneck for rapid fast queries because appendLine will
            // open and close the file each time.  If this becomes the case, we can just set up a
            // blocking queue or something like that, send the line off there, and then have a
            // single thread responsible for writing to the log.  Or even maybe just a shared static
            // output stream -- that would probably be easier and might well be plenty fast.
            FileUtility.appendLine(line, responseLog);
        }
    }

    /**
     * The core of the "plaindoc" action, suitable for invoking outside of Tomcat.
     */
    protected DocumentNLPMutable handlePlainDocCore(String plaintext, DataTools dataTools) {
        try {
            PipelineNLPStanford threadStanfordPipeline = new PipelineNLPStanford(stanfordPipeline);
            PipelineNLPMicro threadMicroPipeline = new PipelineNLPMicro(microPipeline);
            PipelineNLP pipeline = threadStanfordPipeline.weld(threadMicroPipeline);

            DocumentNLPMutable document = new DocumentNLPInMemory(dataTools,
                    "Temporary Document", plaintext);
            pipeline.run(document);
            return document;
        } catch (Exception e) {
            log.error("Caught exception in handlePlainDocCore", e);
            throw new RuntimeException("handlePlainDocCore(\"" + plaintext + "\")", e);
        }
    }

    /**
     * Version of the "plaindoc" action that returns the JSON that would typically be sent back to
     * the web client.
     */
    protected String handlePlainDocJSON(String plaintext) {
        // Go to it.  rawJSON will either be the JSON we'd like to return or JSON containing an
        // error message corresponding to an exception that was thrown during processing of this
        // request.  Either way, we'll log it and return it.
        String rawJSON;
        try {
            MicroDataTools dataTools = new MicroDataTools();
            DocumentNLPMutable document = handlePlainDocCore(plaintext, dataTools);
            SerializerDocumentNLPMicro microSerial = new SerializerDocumentNLPMicro(dataTools);
            rawJSON = microSerial.serializeToString(document);
        } catch (Exception e) {
            // It's handy to also log errors.  This subsumes the exception handler in our caller,
            // but we'll live with it for now.
            log.error("Caught exception in handlePlainDocJSON", e);
	    throw new RuntimeException("Caught Exception", e);
        }
        return rawJSON;
    }

    /**
     * Version of the "plaindoc" action that returns HTML suitable for building demos out of.
     */
    protected String handlePlainDocHTML(String plaintext) {
        // Go to it.  rawHTML will either be the JSON we'd like to return or JSON containing an
        // error message corresponding to an exception that was thrown during processing of this
        // request.  Either way, we'll log it and return it.
        String rawHTML;
        try {
            MicroDataTools dataTools = new MicroDataTools();
            DocumentNLPMutable document = handlePlainDocCore(plaintext, dataTools);
            SerializerDocumentNLPHTML htmlSerial = new SerializerDocumentNLPHTML(dataTools);
            rawHTML = htmlSerial.serializeToString(document);
        } catch (Exception e) {
            // It's handy to also log errors.  This subsumes the exception handler in our caller,
            // but we'll live with it for now.
            log.error("Caught exception in handlePlainDocHTML", e);
	    throw new RuntimeException("Caught Exception", e);
        }
        return rawHTML;
    }

    protected void handlePlainDoc(PrintStream out, String plaintext, HttpServletRequest req) throws Exception {

        // If we have the undocumented GET parameter "fromip", then use that instead of the actual
        // IP making this request.  We may get requests from our webserver on bhealf of remote
        // machines (e.g. via a PHP script) and we use this system so as to be able to log the
        // actual IP instead of just logging that it came from our webserver.
        //
        // We're not doing anything to stop somebody from faking this parameter, but I suppose we
        // could only pay attention to this parameter when the request is coming from our webserver
        // or something like that if it were somehow ever enough of an issue to need to combat.
        String hostmask = req.getParameter("fromip");
        if (hostmask == null) hostmask = req.getRemoteAddr();

        String response;
        String format = req.getParameter("format");
        if (format != null && format.equals("demo")) {
            response = handlePlainDocHTML(plaintext);
        } else {
            // Default to plain JSON, silently ignoring unrecognized format values.
            response = handlePlainDocJSON(plaintext);
        }
           
        rotateResponseLogIfNecessary();
        logResponse(plaintext, response, hostmask);
        out.print(response);
    }

    public void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        PrintStream out = new PrintStream(res.getOutputStream());

        // Wrap the whole thing up in a try block so that we can relay any errors back to the user
        // in JSON form if we want.
        try {
            // Dispatch to the appropriate handler depending on the setting of action
            String action = req.getParameter("action");
            if (action == null) action = "plaindoc";
            if (action.equals("plaindoc")) {
                String plaintext = req.getParameter("text");
                handlePlainDoc(out, plaintext, req);
            } else {
                throw new RuntimeException("Unrecognized action \"" + action + "\"");
            }
        } catch (Exception e) {
            // Our current JSON return format doesn't define a way to differentiate error from
            // success, so just return error as plaintext for now.

            // Downside: we don't get the whole nice stacktrace and full message out of the
            // stacktrace because Java makes it too much work to do that.  But at least we can send
            // it to the logs.
            log.error("Sending back error", e);
            out.println(e.getMessage());
	    System.out.println(e.getMessage());
	    e.printStackTrace();
	    throw new RuntimeException(e);
        }
    }

    public void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        // Supporting both file upload and reading parameters is a PITA in this version of the
        // servlet library, so we simply don't support that.  So, just forward POST requests to the
        // GET handler.
        doGet(req, res);
    }

    /**
     * Testing fixture
     *
     * This is particularly useful to factor out Tomcat-specific problems and to get things working
     * without having to deploy this through Tomcat.
     */
    public static void main(String[] args) {
        try {
            JSON2015Servlet me = new JSON2015Servlet();
            log.info("Initializing...");
            me.init();
            log.info("Initialization done");
            log.info("Invoking the plaindoc action for document " + args[0]);
            System.out.println(me.handlePlainDocCore(args[0], new MicroDataTools()));
            log.info("Success!");
        } catch (Exception e) {
            log.fatal("Uncaught exception", e);
            System.exit(2);
        }
    }
}
