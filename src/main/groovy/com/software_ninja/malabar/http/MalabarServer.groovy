package com.software_ninja.malabar.http;

import java.util.concurrent.Executors
import com.software_ninja.malabar.project.MavenProjectHandler;
import com.software_ninja.malabar.MalabarUtil;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Filter.Chain;
import groovy.util.logging.*
import java.util.logging.Handler
import java.util.logging.Logger
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.ConsoleHandler;

@Log
class MalabarServer {
  def cache = [:];
  def config = [ cache :cache ];

  def start(String port) {

    def mph = new MavenProjectHandler(config);
    def addr = new InetSocketAddress(Integer.parseInt(port))
    def httpServer = com.sun.net.httpserver.HttpServer.create(addr, 0)

    def context = httpServer.createContext('/pi/', new JsonHandlerFactory(config).build({params ->
      def pmfileIn = params["pmfile"];
      def pmfile = (pmfileIn == null ? null : MalabarUtil.expandFile(pmfileIn));
      mph.projectInfo(params["repo"], pmfile);}));
    context.getFilters().add(new ParameterFilter());

    context = httpServer.createContext('/parse/', new JsonHandlerFactory(config).build({params ->
	String pmfileIn = params["pmfile"];
	def pmfile = (pmfileIn == null ? null : MalabarUtil.expandFile(pmfileIn));
	mph.parse(params["repo"], pmfile, params["script"], params["scriptBody"],
		  params['parser']);}));
    context.getFilters().add(new ParameterFilter());

    context = httpServer.createContext('/test/', new JsonHandlerFactory(config).build({params ->
	def pmfileIn = params["pmfile"];
	def pmfile = (pmfileIn == null ? null : MalabarUtil.expandFile(pmfileIn));
	mph.unitTest(params["repo"], pmfile, params["script"], params["method"],
		     params['parser']);}));
    context.getFilters().add(new ParameterFilter());

    context = httpServer.createContext('/exec/', new JsonHandlerFactory(config).build({params ->
	def pmfileIn = params["pmfile"];
	def pmfile = (pmfileIn == null ? null : MalabarUtil.expandFile(pmfileIn));
	mph.exec(params["repo"], pmfile, params["class"], params["arg"]);}));
    context.getFilters().add(new ParameterFilter());

    context = httpServer.createContext('/tags/', new JsonHandlerFactory(config).build({params ->
	def pmfileIn = params["pmfile"];
	def pmfile = (pmfileIn == null ? null : MalabarUtil.expandFile(pmfileIn));
 	mph.tags(params["repo"], pmfile, params["class"]);}));
    context.getFilters().add(new ParameterFilter());

    context = httpServer.createContext('/debug/', new JsonHandlerFactory(config).build({params ->
      def lm = LogManager.getLogManager();
      lm.loggerNames.each( { if( it.startsWith("com.software_ninja")) {
			       def l = lm.getLogger(it);
			       MalabarUtil.setLevel(l, Level.FINEST);
			     }});




      def pmfileIn = params["pmfile"];
      def pmfile = (pmfileIn == null ? null : MalabarUtil.expandFile(pmfileIn));
      mph.debug(params["repo"], pmfile)}));

    context.getFilters().add(new ParameterFilter());

    context = httpServer.createContext('/spawn/', new JsonHandlerFactory(config).build({params ->

      com.software_ninja.malabar.lang.NewVM.startSecondJVM(params["version"], params["jdk"],
							   params["port"], params["cwd"],
							   true);
      [ port : params['port'],
	jdk  : params['jdk'],
	version : params['version'],
	cwd  : System.getProperty("user.dir"),
	"class"  : params['class']] }));
    context.getFilters().add(new ParameterFilter());

    context = httpServer.createContext('/resource/', new JsonHandlerFactory(config).build({params ->
	def pmfileIn = params["pmfile"];
	def pmfile = (pmfileIn == null ? null : MalabarUtil.expandFile(pmfileIn));
	mph.resource(params["repo"], pmfile, params["pattern"], params["max"] as int, params['isClass'] as boolean,
		     params['useRegex'] as boolean);}));
    context.getFilters().add(new ParameterFilter());

    context = httpServer.createContext('/add/', new JsonHandlerFactory(config).build({params ->
      log.fine "ADD: " + params
      mph.additionalClasspath(params["relative"], params["absolute"]);}));
    context.getFilters().add(new ParameterFilter());

    context = httpServer.createContext('/stop/', new JsonHandlerFactory(config).build({params ->  httpServer.stop(1); System.exit(0); }));
    context.getFilters().add(new ParameterFilter());


    httpServer.setExecutor(Executors.newCachedThreadPool())
    httpServer.start()

    log.fine "running on " + port;
    return httpServer;
  }


}

@Log
class ParameterFilter extends Filter {

    @Override
    public String description() {
        return "Parses the requested URI for parameters";
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain)
        throws IOException {
	  log.fine exchange.toString();
	  try {
	    parseGetParameters(exchange);
	    parsePostParameters(exchange);
	    log.fine exchange.getAttribute("parameters").toString();
	  } catch (Exception ex) {
	    ex.printStackTrace();
	  } finally {
	    chain.doFilter(exchange);
	  }
    }

    private void parseGetParameters(HttpExchange exchange)
        throws UnsupportedEncodingException {

        Map<String, Object> parameters = new HashMap<String, Object>();
        URI requestedUri = exchange.getRequestURI();
        String query = requestedUri.getRawQuery();
	//log.fine "GET QUERY:" + query;
        parseQuery(query, parameters);
        exchange.setAttribute("parameters", parameters);
    }

    private void parsePostParameters(HttpExchange exchange)
        throws IOException {

        if ("post".equalsIgnoreCase(exchange.getRequestMethod())) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters =
                (Map<String, Object>)exchange.getAttribute("parameters");
	    //log.fine "POST PARAMETERS:" + parameters;
            InputStreamReader isr =
                new InputStreamReader(exchange.getRequestBody(),"utf-8");
            BufferedReader br = new BufferedReader(isr);
            String query = br.readLine();
	    //log.fine "POST QUERY:" + query;
            parseQuery(query, parameters);
        }
    }

     @SuppressWarnings("unchecked")
     private void parseQuery(String query, Map<String, Object> parameters)
         throws UnsupportedEncodingException {

         if (query != null) {
             String[] pairs = query.split("[&]");

             for (String pair : pairs) {
                 String[] param = pair.split("[=]");

                 String key = null;
                 String value = null;
                 if (param.length > 0) {
                     key = URLDecoder.decode(param[0],
                         System.getProperty("file.encoding"));
                 }

                 if (param.length > 1) {
                     value = URLDecoder.decode(param[1],
                         System.getProperty("file.encoding"));
                 }

                 if (parameters.containsKey(key)) {
                     Object obj = parameters.get(key);
                     if(obj instanceof List<?>) {
                         List<String> values = (List<String>)obj;
                         values.add(value);
                     } else if(obj instanceof String) {
                         List<String> values = new ArrayList<String>();
                         values.add((String)obj);
                         values.add(value);
                         parameters.put(key, values);
                     }
                 } else {
                     parameters.put(key, value);
                 }
             }
         }
    }
}
