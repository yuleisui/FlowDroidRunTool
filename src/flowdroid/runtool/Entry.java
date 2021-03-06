package flowdroid.runtool;

/**
 * Based off the class SetupApplication from package soot.jimple.infoflow.android.TestApps.Test
 * Repository: https://github.com/secure-software-engineering/soot-infoflow-android
 */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.xml.stream.XMLStreamException;




import org.xmlpull.v1.XmlPullParserException;

import soot.SootMethod;
import soot.jimple.Stmt;
import soot.jimple.infoflow.InfoflowConfiguration.AliasingAlgorithm;
import soot.jimple.infoflow.InfoflowConfiguration.CallgraphAlgorithm;
import soot.jimple.infoflow.InfoflowConfiguration.CodeEliminationMode;
import soot.jimple.infoflow.InfoflowConfiguration.DataFlowSolver;
import soot.jimple.infoflow.InfoflowManager;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration.CallbackAnalyzer;
import soot.jimple.infoflow.android.SetupApplication;
import soot.jimple.infoflow.android.source.AndroidSourceSinkManager.LayoutMatchingMode;
import soot.jimple.infoflow.config.IInfoflowConfig;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.data.AccessPath;
import soot.jimple.infoflow.data.pathBuilders.DefaultPathBuilderFactory.PathBuilder;
import soot.jimple.infoflow.handlers.ResultsAvailableHandler;
import soot.jimple.infoflow.ipc.IIPCManager;
import soot.jimple.infoflow.results.InfoflowResults;
import soot.jimple.infoflow.results.ResultSinkInfo;
import soot.jimple.infoflow.results.ResultSourceInfo;
import soot.jimple.infoflow.results.xml.InfoflowResultsSerializer;
import soot.jimple.infoflow.solver.cfg.IInfoflowCFG;
import soot.jimple.infoflow.taintWrappers.EasyTaintWrapper;
import soot.jimple.infoflow.taintWrappers.ITaintPropagationWrapper;
import soot.jimple.infoflow.util.SystemClassHandler;
import soot.options.Options;
import soot.util.HashMultiMap;
import soot.util.MultiMap;
import libsvm.*;

public class Entry {
	
	private static final class MyResultsAvailableHandler implements
			ResultsAvailableHandler {
		private final BufferedWriter wr;

		private MyResultsAvailableHandler() {
			this.wr = null;
		}

		private MyResultsAvailableHandler(BufferedWriter wr) {
			this.wr = wr;
		}

		@Override
		public void onResultsAvailable(
				IInfoflowCFG cfg, InfoflowResults results) {
			// Dump the results
			if (results == null) {
				print("No results found.");
			}
			else {
				// Report the results
				for (ResultSinkInfo sink : results.getResults().keySet()) {
					if (config.isIccEnabled() && config.isIccResultsPurifyEnabled()) {
						print("Found an ICC flow to sink " + sink + ", from the following sources:");
					}
					else {
						print("Found a flow to sink " + sink + ", from the following sources:");
					}
					
					for (ResultSourceInfo source : results.getResults().get(sink)) {
						print("\t- " + source.getSource() + " (in "
								+ cfg.getMethodOf(source.getSource()).getSignature()  + ")");
						if (source.getPath() != null)
							print("\t\ton Path " + Arrays.toString(source.getPath()));
					}
				}
				
				// Serialize the results if requested
				// Write the results into a file if requested
				if (resultFilePath != null && !resultFilePath.isEmpty()) {
					InfoflowResultsSerializer serializer = new InfoflowResultsSerializer(cfg, config);
					try {
						serializer.serialize(results, resultFilePath);
					} catch (FileNotFoundException ex) {
						System.err.println("Could not write data flow results to file: " + ex.getMessage());
						ex.printStackTrace();
						throw new RuntimeException(ex);
					} catch (XMLStreamException ex) {
						System.err.println("Could not write data flow results to file: " + ex.getMessage());
						ex.printStackTrace();
						throw new RuntimeException(ex);
					}
				}
			}
			
		}

		private void print(String string) {
			try {
				System.out.println(string);
				if (wr != null)
					wr.write(string + "\n");
			}
			catch (IOException ex) {
				// ignore
			}
		}
	}
	
	private static InfoflowAndroidConfiguration config = new InfoflowAndroidConfiguration();
	
	private static int repeatCount = 1;
	private static int timeout = -1;
	private static int sysTimeout = -1;
	
	private static boolean aggressiveTaintWrapper = false;
	private static boolean noTaintWrapper = false;
	private static String summaryPath = "";
	private static String resultFilePath = "";

	private static IIPCManager ipcManager = null;
	
	private static String logFilePath = null;
	
	public static void setIPCManager(IIPCManager ipcManager)
	{
		Entry.ipcManager = ipcManager;
	}
	public static IIPCManager getIPCManager()
	{
		return Entry.ipcManager;
	}
	public enum RunCommand {
		Compare,
		DetermineOption,
		RunFlowDroid,
		GetProperties,
		Invalid
	}
	
	private static RunCommand runCommand = RunCommand.Invalid;
	private static String configName = "";
	private static Map<String, InfoflowAndroidConfiguration> runOptions;
	private static boolean reportAllFlows = false;
	
	public static boolean parseCommands(String[] args) {
		String command = args[2];
		if (command.equalsIgnoreCase("compare")) {
			runCommand = RunCommand.Compare;
			if (args.length > 3) {
				runOptions = setupRunOptions(args[3]);
				int i = 4;
				while (i < args.length) {
					if (args[i].equalsIgnoreCase("--log")) {
						logFilePath = args[i + 1];
						i += 2;
					} else if (args[i].equalsIgnoreCase("--reportallflows")) {
						reportAllFlows = true;
						i++;
					}
				}
				return true;
			}
		} else if (command.equalsIgnoreCase("determineoption")) {
			runCommand = RunCommand.DetermineOption;
			int i = 3;
			while (i < args.length) {
				if (args[i].equalsIgnoreCase("--log")) {
					logFilePath = args[i + 1];
					i += 2;
				}
			}
			return true;
		} else if (command.equalsIgnoreCase("getproperties")) {
			runCommand = RunCommand.GetProperties;
			if (args.length > 3) {
				configName = args[3];
				config = parseAdditionalOptions(args);
				if (config == null) {
					return false;
				}
				if (!validateAdditionalOptions(config)) {
					return false;
				};
				return true;
			}
		} else if (command.equalsIgnoreCase("run")) {
			runCommand = RunCommand.RunFlowDroid;
			config = parseAdditionalOptions(args);
			if (config == null) {
				return false;
			}
			if (!validateAdditionalOptions(config)) {
				return false;
			};
			return true;
		}
		
		return false;
	}
	
	/**
	 * @param args Program arguments. args[0] = path to apk-file,
	 * args[1] = path to android-dir (path/android-platforms/)
	 * args[2] = path to file containing configuration options to run
	 */
	public static void main(final String[] args) throws IOException, InterruptedException {
		if (args.length < 3) {
			printUsage();
			return;
		} else if (args[0] == "--help") {
			printUsage();
			return;
		}
		
		File outputDir = new File("JimpleOutput");
		if (outputDir.isDirectory()){
			boolean success = true;
			for(File f : outputDir.listFiles()){
				success = success && f.delete();
			}
			if(!success){
				System.err.println("Cleanup of output directory "+ outputDir + " failed!");
			}
			outputDir.delete();
		}
		
		final String fullFilePath = args[0];
		final String platformsPath = args[1];
		
		// Parse additional command-line arguments
		if (!parseCommands(args)) {
			printUsage();
			return;
		}
		
		//Stores options to run
		
		System.gc();

		// Run the analysis
		switch (runCommand) {
		case Compare:
			runAnalysisComparison(fullFilePath, platformsPath, runOptions);
			break;
		case DetermineOption:
			determineOption(fullFilePath, platformsPath);
			break;
		case RunFlowDroid:
			while (repeatCount > 0) {
				System.gc();

				if (timeout > 0)
					runAnalysisTimeout(fullFilePath, platformsPath);
				else if (sysTimeout > 0)
					runAnalysisSysTimeout(fullFilePath, platformsPath);
				else {
					runAnalysis(fullFilePath, platformsPath);
					//runAnalysisComparison(fullFilePath, args[1], runOptions);
				}
				repeatCount--;
			}
			break;
		case GetProperties:
			runAnalysisGetProperties(fullFilePath, platformsPath, configName, config);
		default:
			break;
		}
		
	}

	/**
	 * Parses the optional command-line arguments
	 * @param args The array of arguments to parse
	 * @return True if all arguments are valid and could be parsed, otherwise
	 * false
	 */
	@SuppressWarnings("deprecation")
	private static InfoflowAndroidConfiguration parseAdditionalOptions(String[] args) {
		InfoflowAndroidConfiguration config = new InfoflowAndroidConfiguration(); 
		int i = 0;
		while (i < args.length) {
			if (args[i].equalsIgnoreCase("--timeout")) {
				int realTimeout = Integer.valueOf(args[i+1]);
				timeout = realTimeout + 1;
				config.setDataFlowTimeout(realTimeout);
				i += 2;
			}

			else if (args[i].equalsIgnoreCase("--callbacktimeout")) {
				int realTimeout = Integer.valueOf(args[i+1]);
				timeout = realTimeout + 1;
				config.setCallbackAnalysisTimeout(realTimeout);
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--resulttimeout")) {
				int realTimeout = Integer.valueOf(args[i+1]);
				timeout = realTimeout + 1;
				config.setResultSerializationTimeout(realTimeout);
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--systimeout")) {
				sysTimeout = Integer.valueOf(args[i+1]);
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--singleflow")) {
				config.setStopAfterFirstFlow(true);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--implicit")) {
				config.setEnableImplicitFlows(true);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--nostatic")) {
				config.setEnableStaticFieldTracking(false);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--aplength")) {
				config.setAccessPathLength(Integer.valueOf(args[i+1]));
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--cgalgo")) {
				String algo = args[i+1];
				if (algo.equalsIgnoreCase("AUTO"))
					config.setCallgraphAlgorithm(CallgraphAlgorithm.AutomaticSelection);
				else if (algo.equalsIgnoreCase("CHA"))
					config.setCallgraphAlgorithm(CallgraphAlgorithm.CHA);
				else if (algo.equalsIgnoreCase("VTA"))
					config.setCallgraphAlgorithm(CallgraphAlgorithm.VTA);
				else if (algo.equalsIgnoreCase("RTA"))
					config.setCallgraphAlgorithm(CallgraphAlgorithm.RTA);
				else if (algo.equalsIgnoreCase("SPARK"))
					config.setCallgraphAlgorithm(CallgraphAlgorithm.SPARK);
				else if (algo.equalsIgnoreCase("GEOM"))
					config.setCallgraphAlgorithm(CallgraphAlgorithm.GEOM);
				else {
					System.err.println("Invalid callgraph algorithm");
					return null;
				}
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--nocallbacks")) {
				config.setEnableCallbacks(false);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--noexceptions")) {
				config.setEnableExceptionTracking(false);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--layoutmode")) {
				String algo = args[i+1];
				if (algo.equalsIgnoreCase("NONE"))
					config.setLayoutMatchingMode(LayoutMatchingMode.NoMatch);
				else if (algo.equalsIgnoreCase("PWD"))
					config.setLayoutMatchingMode(LayoutMatchingMode.MatchSensitiveOnly);
				else if (algo.equalsIgnoreCase("ALL"))
					config.setLayoutMatchingMode(LayoutMatchingMode.MatchAll);
				else {
					System.err.println("Invalid layout matching mode");
					return null;
				}
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--aliasflowins")) {
				config.setFlowSensitiveAliasing(false);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--paths")) {
				config.setComputeResultPaths(true);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--nopaths")) {
				config.setComputeResultPaths(false);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--aggressivetw")) {
				aggressiveTaintWrapper = false;
				i++;
			}
			else if (args[i].equalsIgnoreCase("--pathalgo")) {
				String algo = args[i+1];
				if (algo.equalsIgnoreCase("CONTEXTSENSITIVE"))
					config.setPathBuilder(PathBuilder.ContextSensitive);
				else if (algo.equalsIgnoreCase("CONTEXTINSENSITIVE"))
					config.setPathBuilder(PathBuilder.ContextInsensitive);
				else if (algo.equalsIgnoreCase("SOURCESONLY"))
					config.setPathBuilder(PathBuilder.ContextInsensitiveSourceFinder);
				else {
					System.err.println("Invalid path reconstruction algorithm");
					return null;
				}
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--summarypath")) {
				summaryPath = args[i + 1];
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--saveresults")) {
				resultFilePath = args[i + 1];
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--sysflows")) {
				config.setIgnoreFlowsInSystemPackages(false);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--notaintwrapper")) {
				noTaintWrapper = true;
				i++;
			}
			else if (args[i].equalsIgnoreCase("--notypechecking")) {
				config.setEnableTypeChecking(false);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--repeatcount")) {
				repeatCount = Integer.parseInt(args[i + 1]);
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--noarraysize")) {
				config.setEnableArraySizeTainting(false);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--arraysize")) {
				config.setEnableArraySizeTainting(true);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--safemode")) {
				config.setUseThisChainReduction(false);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--logsourcesandsinks")) {
				config.setLogSourcesAndSinks(true);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--callbackanalyzer")) {
				String algo = args[i+1];
				if (algo.equalsIgnoreCase("DEFAULT"))
					config.setCallbackAnalyzer(CallbackAnalyzer.Default);
				else if (algo.equalsIgnoreCase("FAST"))
					config.setCallbackAnalyzer(CallbackAnalyzer.Fast);
				else {
					System.err.println("Invalid callback analysis algorithm");
					return null;
				}
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--maxthreadnum")){
				config.setMaxThreadNum(Integer.valueOf(args[i+1]));
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--arraysizetainting")) {
				config.setEnableArraySizeTainting(true);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--dataflowsolver")) {
				String algo = args[i+1];
				if (algo.equalsIgnoreCase("HEROS"))
					config.setDataFlowSolver(DataFlowSolver.Heros);
				else if (algo.equalsIgnoreCase("CONTEXTFLOWSENSITIVE"))
					config.setDataFlowSolver(DataFlowSolver.ContextFlowSensitive);
				else if (algo.equalsIgnoreCase("FLOWINSENSITIVE"))
					config.setDataFlowSolver(DataFlowSolver.FlowInsensitive);
				else {
					System.err.println("Invalid data flow algorithm");
					return null;
				}
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--iccmodel")) {
				config.setIccModel(args[i+1]);
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--noiccresultspurify")) {
				config.setIccResultsPurify(false);
				i++;
			}


			else if (args[i].equalsIgnoreCase("--onecomponentatatime")) {
				config.setOneComponentAtATime(true);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--onesourceatatime")) {
				config.setOneSourceAtATime(true);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--aliasalgo")) {
				String algo = args[i+1];
				if (algo.equalsIgnoreCase("NONE"))
					config.setAliasingAlgorithm(AliasingAlgorithm.None);
				else if (algo.equalsIgnoreCase("FLOWSENSITIVE"))
					config.setAliasingAlgorithm(AliasingAlgorithm.FlowSensitive);
				else if (algo.equalsIgnoreCase("PTSBASED"))
					config.setAliasingAlgorithm(AliasingAlgorithm.PtsBased);
				else if (algo.equalsIgnoreCase("LAZY"))
					config.setAliasingAlgorithm(AliasingAlgorithm.Lazy);
				else {
					System.err.println("Invalid aliasing algorithm");
					return null;
				}
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--codeelimination")) {
				String algo = args[i+1];
				if (algo.equalsIgnoreCase("NONE"))
					config.setCodeEliminationMode(CodeEliminationMode.NoCodeElimination);
				else if (algo.equalsIgnoreCase("PROPAGATECONSTS"))
					config.setCodeEliminationMode(CodeEliminationMode.PropagateConstants);
				else if (algo.equalsIgnoreCase("REMOVECODE"))
					config.setCodeEliminationMode(CodeEliminationMode.RemoveSideEffectFreeCode);
				else {
					System.err.println("Invalid code elimination mode");
					return null;
				}
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--enablereflection")) {
				config.setEnableRefection(true);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--sequentialpathprocessing")) {
				config.setSequentialPathProcessing(true);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--singlejoinpointabstraction")) {
				config.setSingleJoinPointAbstraction(true);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--nocallbacksources")) {
				config.setEnableCallbackSources(false);
				i++;
			}
			else if (args[i].equalsIgnoreCase("--maxcallbackspercomponent")) {
				config.setMaxCallbacksPerComponent(Integer.valueOf(args[i+1]));
				i += 2;
			}
			else if (args[i].equalsIgnoreCase("--incrementalresults")) {
				config.setIncrementalResultReporting(true);
				i++;
			}
			else
				i++;
		}
		return config;
	}
	
	private static boolean validateAdditionalOptions(InfoflowAndroidConfiguration config) {
		if (timeout > 0 && sysTimeout > 0) {
			return false;
		}
		if (!config.getFlowSensitiveAliasing()
				&& config.getAliasingAlgorithm() != AliasingAlgorithm.FlowSensitive) {
			System.err.println("Flow-insensitive aliasing can only be configured for callgraph "
					+ "algorithms that support this choice.");
			return false;
		}
		return true;
	}
	
	private static void runAnalysisTimeout(final String fileName, final String androidJar) {
		FutureTask<InfoflowResults> task = new FutureTask<InfoflowResults>(new Callable<InfoflowResults>() {

			@Override
			public InfoflowResults call() throws Exception {
				
				final BufferedWriter wr = new BufferedWriter(new FileWriter("_out_" + new File(fileName).getName() + ".txt"));
				try {
					final long beforeRun = System.nanoTime();
					wr.write("Running data flow analysis...\n");
					final InfoflowResults res = runAnalysis(fileName, androidJar);
					wr.write("Analysis has run for " + (System.nanoTime() - beforeRun) / 1E9 + " seconds\n");
					
					wr.flush();
					return res;
				}
				finally {
					if (wr != null)
						wr.close();
				}
			}
			
		});
		ExecutorService executor = Executors.newFixedThreadPool(1);
		executor.execute(task);
		
		try {
			System.out.println("Running infoflow task...");
			task.get(timeout, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			System.err.println("Infoflow computation failed: " + e.getMessage());
			e.printStackTrace();
		} catch (TimeoutException e) {
			// This is expected, do not report it
		} catch (InterruptedException e) {
			System.err.println("Infoflow computation interrupted: " + e.getMessage());
			e.printStackTrace();
		}
		
		// Make sure to remove leftovers
		executor.shutdown();		
	}

	private static void runAnalysisSysTimeout(final String fileName, final String androidJar) {
		String classpath = System.getProperty("java.class.path");
		String javaHome = System.getProperty("java.home");
		String executable = "/usr/bin/timeout";
		String[] command = new String[] { executable,
				"-s", "KILL",
				sysTimeout + "s",
				javaHome + "/bin/java",
				"-cp", classpath,
				"soot.jimple.infoflow.android.TestApps.Test",
				fileName,
				androidJar,
				config.getStopAfterFirstFlow() ? "--singleflow" : "--nosingleflow",
				config.getEnableImplicitFlows() ? "--implicit" : "--noimplicit",
				config.getEnableStaticFieldTracking() ? "--static" : "--nostatic", 
				"--aplength", Integer.toString(config.getAccessPathLength()),
				"--cgalgo", callgraphAlgorithmToString(config.getCallgraphAlgorithm()),
				config.getEnableCallbacks() ? "--callbacks" : "--nocallbacks",
				config.getEnableExceptionTracking() ? "--exceptions" : "--noexceptions",
				"--layoutmode", layoutMatchingModeToString(config.getLayoutMatchingMode()),
				config.getFlowSensitiveAliasing() ? "--aliasflowsens" : "--aliasflowins",
				config.getComputeResultPaths() ? "--paths" : "--nopaths",
				aggressiveTaintWrapper ? "--aggressivetw" : "--nonaggressivetw",
				"--pathalgo", pathAlgorithmToString(config.getPathBuilder()),
				(summaryPath != null && !summaryPath.isEmpty()) ? "--summarypath" : "",
				(summaryPath != null && !summaryPath.isEmpty()) ? summaryPath : "",
				(resultFilePath != null && !resultFilePath.isEmpty()) ? "--saveresults" : "",
				noTaintWrapper ? "--notaintwrapper" : "",
				config.getEnableTypeChecking() ? "" : "--notypechecking",
//				"--repeatCount", Integer.toString(repeatCount),
				config.getEnableArraySizeTainting() ? "" : "--noarraysize",
				config.getUseThisChainReduction() ? "" : "--safemode",
				config.getLogSourcesAndSinks() ? "--logsourcesandsinks" : "",
				"--callbackanalyzer", callbackAlgorithmToString(config.getCallbackAnalyzer()),
				"--maxthreadnum", Integer.toString(config.getMaxThreadNum()),
				config.getEnableArraySizeTainting() ? "--arraysizetainting" : "",
				config.getEnableArraySizeTainting() ? "--arraysizetainting" : "",
				config.isIccEnabled() ? "--iccmodel " + config.getIccModel() : "",
				config.getOneComponentAtATime() ? "--onecomponentatatime" : "",
				"--aliasalgo", aliasAlgorithmToString(config.getAliasingAlgorithm()),
				"--codeelimination", codeEliminationModeToString(config.getCodeEliminationMode()),
				config.getEnableReflection() ? "--enablereflection" : "",
				config.getEnableCallbackSources() ? "" : "--nocallbacksources",
				};
		System.out.println("Running command: " + executable + " " + Arrays.toString(command));
		try {
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.redirectOutput(new File("out_" + new File(fileName).getName() + "_" + repeatCount + ".txt"));
			pb.redirectError(new File("err_" + new File(fileName).getName() + "_" + repeatCount + ".txt"));
			Process proc = pb.start();
			proc.waitFor();
		} catch (IOException ex) {
			System.err.println("Could not execute timeout command: " + ex.getMessage());
			ex.printStackTrace();
		} catch (InterruptedException ex) {
			System.err.println("Process was interrupted: " + ex.getMessage());
			ex.printStackTrace();
		}
	}
	
	private static String callgraphAlgorithmToString(CallgraphAlgorithm algorihm) {
		switch (algorihm) {
			case AutomaticSelection:
				return "AUTO";
			case CHA:
				return "CHA";
			case VTA:
				return "VTA";
			case RTA:
				return "RTA";
			case SPARK:
				return "SPARK";
			case GEOM:
				return "GEOM";
			default:
				return "unknown";
		}
	}

	private static String layoutMatchingModeToString(LayoutMatchingMode mode) {
		switch (mode) {
			case NoMatch:
				return "NONE";
			case MatchSensitiveOnly:
				return "PWD";
			case MatchAll:
				return "ALL";
			default:
				return "unknown";
		}
	}
	
	private static String pathAlgorithmToString(PathBuilder pathBuilder) {
		switch (pathBuilder) {
			case ContextSensitive:
				return "CONTEXTSENSITIVE";
			case ContextInsensitive :
				return "CONTEXTINSENSITIVE";
			case ContextInsensitiveSourceFinder :
				return "SOURCESONLY";
			default :
				return "UNKNOWN";
		}
	}
	
	private static String callbackAlgorithmToString(CallbackAnalyzer analyzer) {
		switch (analyzer) {
			case Default:
				return "DEFAULT";
			case Fast:
				return "FAST";
			default :
				return "UNKNOWN";
		}
	}

	private static String aliasAlgorithmToString(AliasingAlgorithm algo) {
		switch (algo) {
			case None:
				return "NONE";
			case Lazy:
				return "LAZY";
			case FlowSensitive:
				return "FLOWSENSITIVE";
			case PtsBased:
				return "PTSBASED";
			default :
				return "UNKNOWN";
		}
	}

	private static String codeEliminationModeToString(CodeEliminationMode mode) {
		switch (mode) {
			case NoCodeElimination:
				return "NONE";
			case PropagateConstants:
				return "PROPAGATECONSTS";
			case RemoveSideEffectFreeCode:
				return "REMOVECODE";
			default :
				return "UNKNOWN";
		}
	}

	private static ApplicationProperties getApplicationProperties(final String fileName, final String androidJar, InfoflowAndroidConfiguration analysisConfig) {
		try {
			System.gc();

			final SetupApplicationWithProperties app;
			if (null == ipcManager)
			{
				app = new SetupApplicationWithProperties(androidJar, fileName);
			}
			else
			{
				app = new SetupApplicationWithProperties(androidJar, fileName, ipcManager);
			}
			
			// Set configuration object
			app.setConfig(analysisConfig);
			
			if (analysisConfig.isIccEnabled())
			{
				//Set instrumentation object
				analysisConfig.setCodeEliminationMode(CodeEliminationMode.NoCodeElimination);
				analysisConfig.setPathBuilder(PathBuilder.ContextSensitive);
			}
			
			if (noTaintWrapper)
				app.setSootConfig(new IInfoflowConfig() {
					
					@Override
					public void setSootOptions(Options options) {
						options.set_include_all(true);
					}
				});
			
			final ITaintPropagationWrapper taintWrapper;
			if (noTaintWrapper)
				taintWrapper = null;
			else if (summaryPath != null && !summaryPath.isEmpty()) {
				System.out.println("Using the StubDroid taint wrapper");
				taintWrapper = createLibrarySummaryTW();
				if (taintWrapper == null) {
					System.err.println("Could not initialize StubDroid");
					return null;
				}
			}
			else {
				final EasyTaintWrapper easyTaintWrapper;
				File twSourceFile = new File("../soot-infoflow/EasyTaintWrapperSource.txt");
				if (twSourceFile.exists())
					easyTaintWrapper = new EasyTaintWrapper(twSourceFile);
				else {
					twSourceFile = new File("EasyTaintWrapperSource.txt");
					if (twSourceFile.exists())
						easyTaintWrapper = new EasyTaintWrapper(twSourceFile);
					else {
						System.err.println("Taint wrapper definition file not found at "
								+ twSourceFile.getAbsolutePath());
						return null;
					}
				}
				easyTaintWrapper.setAggressiveMode(aggressiveTaintWrapper);
				taintWrapper = easyTaintWrapper;
			}
			app.setTaintWrapper(taintWrapper);
			ApplicationProperties appProps = app.runInfoflowProperties("SourcesAndSinks.txt");

			app.addResultsAvailableHandler(new MyResultsAvailableHandler());
			
			return appProps;
		} catch (IOException ex) {
			System.err.println("Could not read file: " + ex.getMessage());
			ex.printStackTrace();
			throw new RuntimeException(ex);
		} catch (XmlPullParserException ex) {
			System.err.println("Could not read Android manifest file: " + ex.getMessage());
			ex.printStackTrace();
			throw new RuntimeException(ex);
		}
	}
	
	private static InfoflowResults runAnalysis(final String fileName, final String androidJar) {
		try {
			final long beforeRun = System.nanoTime();

			final SetupApplication app;
			if (null == ipcManager)
			{
				app = new SetupApplication(androidJar, fileName);
			}
			else
			{
				app = new SetupApplication(androidJar, fileName, ipcManager);
			}
			
			// Set configuration object
			app.setConfig(config);
			
			
			if (config.isIccEnabled())
			{
				//Set instrumentation object
				config.setCodeEliminationMode(CodeEliminationMode.NoCodeElimination);
				config.setPathBuilder(PathBuilder.ContextSensitive);
			}
			
			if (noTaintWrapper)
				app.setSootConfig(new IInfoflowConfig() {
					
					@Override
					public void setSootOptions(Options options) {
						options.set_include_all(true);
					}
					
				});
			
			final ITaintPropagationWrapper taintWrapper;
			if (noTaintWrapper)
				taintWrapper = null;
			else if (summaryPath != null && !summaryPath.isEmpty()) {
				System.out.println("Using the StubDroid taint wrapper");
				taintWrapper = createLibrarySummaryTW();
				if (taintWrapper == null) {
					System.err.println("Could not initialize StubDroid");
					return null;
				}
			}
			else {
				final EasyTaintWrapper easyTaintWrapper;
				File twSourceFile = new File("../soot-infoflow/EasyTaintWrapperSource.txt");
				if (twSourceFile.exists())
					easyTaintWrapper = new EasyTaintWrapper(twSourceFile);
				else {
					twSourceFile = new File("EasyTaintWrapperSource.txt");
					if (twSourceFile.exists())
						easyTaintWrapper = new EasyTaintWrapper(twSourceFile);
					else {
						System.err.println("Taint wrapper definition file not found at "
								+ twSourceFile.getAbsolutePath());
						return null;
					}
				}
				easyTaintWrapper.setAggressiveMode(aggressiveTaintWrapper);
				taintWrapper = easyTaintWrapper;
			}
			app.setTaintWrapper(taintWrapper);
			
			System.out.println("Running data flow analysis...");
			
			app.addResultsAvailableHandler(new MyResultsAvailableHandler());
			final InfoflowResults res = app.runInfoflow("SourcesAndSinks.txt");
			System.out.println("Analysis has run for " + (System.nanoTime() - beforeRun) / 1E9 + " seconds");
			for (ResultSinkInfo sink : res.getResults().keySet()) {
				for (ResultSourceInfo source: res.getResults().get(sink)) {
					System.out.println(source + " TO " + sink);
				}
			}
			if (config.getLogSourcesAndSinks()) {
				if (!app.getCollectedSources().isEmpty()) {
					System.out.println("Collected sources:");
					for (Stmt s : app.getCollectedSources())
						System.out.println("\t" + s);
				}
				if (!app.getCollectedSinks().isEmpty()) {
					System.out.println("Collected sinks:");
					for (Stmt s : app.getCollectedSinks())
						System.out.println("\t" + s);
				}
			}
			
			return res;
		} catch (IOException ex) {
			System.err.println("Could not read file: " + ex.getMessage());
			ex.printStackTrace();
			throw new RuntimeException(ex);
		} catch (XmlPullParserException ex) {
			System.err.println("Could not read Android manifest file: " + ex.getMessage());
			ex.printStackTrace();
			throw new RuntimeException(ex);
		}
	}
	
	private static InfoflowResultsData runAnalysis(final String fileName, final String androidJar, InfoflowAndroidConfiguration analysisConfig) {
		try {
			System.gc();
			final long beforeRun = System.nanoTime();

			final SetupApplication app;
			if (null == ipcManager)
			{
				app = new SetupApplication(androidJar, fileName);
			}
			else
			{
				app = new SetupApplication(androidJar, fileName, ipcManager);
			}
			
			// Set configuration object
			app.setConfig(analysisConfig);
			
			
			if (analysisConfig.isIccEnabled())
			{
				//Set instrumentation object
				analysisConfig.setCodeEliminationMode(CodeEliminationMode.NoCodeElimination);
				analysisConfig.setPathBuilder(PathBuilder.ContextSensitive);
			}
			
			if (noTaintWrapper)
				app.setSootConfig(new IInfoflowConfig() {
					
					@Override
					public void setSootOptions(Options options) {
						options.set_include_all(true);
					}
					
				});
			
			final ITaintPropagationWrapper taintWrapper;
			if (noTaintWrapper)
				taintWrapper = null;
			else if (summaryPath != null && !summaryPath.isEmpty()) {
				System.out.println("Using the StubDroid taint wrapper");
				taintWrapper = createLibrarySummaryTW();
				if (taintWrapper == null) {
					System.err.println("Could not initialize StubDroid");
					return null;
				}
			}
			else {
				final EasyTaintWrapper easyTaintWrapper;
				File twSourceFile = new File("../soot-infoflow/EasyTaintWrapperSource.txt");
				if (twSourceFile.exists())
					easyTaintWrapper = new EasyTaintWrapper(twSourceFile);
				else {
					twSourceFile = new File("EasyTaintWrapperSource.txt");
					if (twSourceFile.exists())
						easyTaintWrapper = new EasyTaintWrapper(twSourceFile);
					else {
						System.err.println("Taint wrapper definition file not found at "
								+ twSourceFile.getAbsolutePath());
						return null;
					}
				}
				easyTaintWrapper.setAggressiveMode(aggressiveTaintWrapper);
				taintWrapper = easyTaintWrapper;
			}
			app.setTaintWrapper(taintWrapper);
			
			System.out.println("Running data flow analysis...");

			app.addResultsAvailableHandler(new MyResultsAvailableHandler());
			InfoflowResults res = app.runInfoflow("SourcesAndSinks.txt");
			InfoflowResultsData resData = new InfoflowResultsData(res);
			
			double runTime = (double) ((System.nanoTime() - beforeRun) / 1E9);
			double maxMemory = (double) (app.getMaxMemoryConsumption() / 1E6);
			resData.setRunTime(runTime);
			resData.setMaxMemoryConsumption(maxMemory);
			
			System.out.println("Analysis has run for " + runTime + " seconds");
			
			if (analysisConfig.getLogSourcesAndSinks()) {
				if (!app.getCollectedSources().isEmpty()) {
					System.out.println("Collected sources:");
					for (Stmt s : app.getCollectedSources())
						System.out.println("\t" + s);
				}
				if (!app.getCollectedSinks().isEmpty()) {
					System.out.println("Collected sinks:");
					for (Stmt s : app.getCollectedSinks())
						System.out.println("\t" + s);
				}
			}
			
			return resData;
		} catch (IOException ex) {
			System.err.println("Could not read file: " + ex.getMessage());
			ex.printStackTrace();
			throw new RuntimeException(ex);
		} catch (XmlPullParserException ex) {
			System.err.println("Could not read Android manifest file: " + ex.getMessage());
			ex.printStackTrace();
			throw new RuntimeException(ex);
		}
	}
	
	/**
	 * Creates the taint wrapper for using library summaries
	 * @return The taint wrapper for using library summaries
	 * @throws IOException Thrown if one of the required files could not be read
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static ITaintPropagationWrapper createLibrarySummaryTW()
			throws IOException {
		try {
			Class clzLazySummary = Class.forName("soot.jimple.infoflow.methodSummary.data.provider.LazySummaryProvider");
			Class itfLazySummary = Class.forName("soot.jimple.infoflow.methodSummary.data.provider.IMethodSummaryProvider");
			
			Object lazySummary = clzLazySummary.getConstructor(File.class).newInstance(new File(summaryPath));
			
			ITaintPropagationWrapper summaryWrapper = (ITaintPropagationWrapper) Class.forName
					("soot.jimple.infoflow.methodSummary.taintWrappers.SummaryTaintWrapper").getConstructor
					(itfLazySummary).newInstance(lazySummary);
			
			ITaintPropagationWrapper systemClassWrapper = new ITaintPropagationWrapper() {
				
				private ITaintPropagationWrapper wrapper = new EasyTaintWrapper("EasyTaintWrapperSource.txt");
				
				private boolean isSystemClass(Stmt stmt) {
					if (stmt.containsInvokeExpr())
						return SystemClassHandler.isClassInSystemPackage(
								stmt.getInvokeExpr().getMethod().getDeclaringClass().getName());
					return false;
				}
				
				@Override
				public boolean supportsCallee(Stmt callSite) {
					return isSystemClass(callSite) && wrapper.supportsCallee(callSite);
				}
				
				@Override
				public boolean supportsCallee(SootMethod method) {
					return SystemClassHandler.isClassInSystemPackage(method.getDeclaringClass().getName())
							&& wrapper.supportsCallee(method);
				}
				
				@Override
				public boolean isExclusive(Stmt stmt, Abstraction taintedPath) {
					return isSystemClass(stmt) && wrapper.isExclusive(stmt, taintedPath);
				}
				
				@Override
				public void initialize(InfoflowManager manager) {
					wrapper.initialize(manager);
				}
				
				@Override
				public int getWrapperMisses() {
					return 0;
				}
				
				@Override
				public int getWrapperHits() {
					return 0;
				}
				
				@Override
				public Set<Abstraction> getTaintsForMethod(Stmt stmt, Abstraction d1,
						Abstraction taintedPath) {
					if (!isSystemClass(stmt))
						return null;
					return wrapper.getTaintsForMethod(stmt, d1, taintedPath);
				}
				
				@Override
				public Set<Abstraction> getAliasesForMethod(Stmt stmt, Abstraction d1,
						Abstraction taintedPath) {
					if (!isSystemClass(stmt))
						return null;
					return wrapper.getAliasesForMethod(stmt, d1, taintedPath);
				}
				
			};
			
			Method setFallbackMethod = summaryWrapper.getClass().getMethod("setFallbackTaintWrapper",
					ITaintPropagationWrapper.class);
			setFallbackMethod.invoke(summaryWrapper, systemClassWrapper);
			
			return summaryWrapper;
		}
		catch (ClassNotFoundException | NoSuchMethodException ex) {
			System.err.println("Could not find library summary classes: " + ex.getMessage());
			ex.printStackTrace();
			return null;
		}
		catch (InvocationTargetException ex) {
			System.err.println("Could not initialize library summaries: " + ex.getMessage());
			ex.printStackTrace();
			return null;
		}
		catch (IllegalAccessException | InstantiationException ex) {
			System.err.println("Internal error in library summary initialization: " + ex.getMessage());
			ex.printStackTrace();
			return null;
		}
	}

	private static void printUsage() {
		System.out.println("FlowDroid Run Tool");
		System.out.println();
		System.out.println("Argument usage:");
		System.out.println("<apk_file> <platforms directory> compare <options_file> [--reportallflows --log <output_file>]");
		System.out.println("<apk_file> <platforms directory> determineoption [--log <output_file>]");
		System.out.println("<apk_file> <platforms directory> run [FlowDroid options]");
		System.out.println("FlowDroid options for run:");
		System.out.println("\t--TIMEOUT n Time out after n seconds (data flow only)");
		System.out.println("\t--PATHTIMEOUT n Time out after n seconds (path reconstruction only)");
		System.out.println("\t--CALLBACKTIMEOUT n Time out after n seconds (callback collection only)");
		System.out.println("\t--SYSTIMEOUT n Hard time out (kill process) after n seconds, Unix only");
		System.out.println("\t--SINGLEFLOW Stop after finding first leak");
		System.out.println("\t--IMPLICIT Enable implicit flows");
		System.out.println("\t--NOSTATIC Disable static field tracking");
		System.out.println("\t--NOEXCEPTIONS Disable exception tracking");
		System.out.println("\t--APLENGTH n Set access path length to n");
		System.out.println("\t--CGALGO x Use callgraph algorithm x");
		System.out.println("\t--NOCALLBACKS Disable callback analysis");
		System.out.println("\t--LAYOUTMODE x Set UI control analysis mode to x");
		System.out.println("\t--ALIASFLOWINS Use a flow insensitive alias search");
		System.out.println("\t--NOPATHS Do not compute result paths");
		System.out.println("\t--AGGRESSIVETW Use taint wrapper in aggressive mode");
		System.out.println("\t--PATHALGO Use path reconstruction algorithm x");
		System.out.println("\t--SUMMARYPATH Path to library summaries");
		System.out.println("\t--SYSFLOWS Also analyze classes in system packages");
		System.out.println("\t--NOTAINTWRAPPER Disables the use of taint wrappers");
		System.out.println("\t--NOTYPECHECKING Do not propagate types along with taints");
		System.out.println("\t--LOGSOURCESANDSINKS Print out concrete source/sink instances");
		System.out.println("\t--CALLBACKANALYZER x Uses callback analysis algorithm x");
		System.out.println("\t--MAXTHREADNUM x Sets the maximum number of threads to be used by the analysis to x");
		System.out.println("\t--ONECOMPONENTATATIME Analyze one component at a time");
		System.out.println("\t--ONESOURCEATATIME Analyze one source at a time");
		System.out.println("\t--ALIASALGO x Use the aliasing algorithm x");
		System.out.println("\t--CODEELIMINATION x Use code elimination mode x");
		System.out.println("\t--ENABLEREFLECTION Enable support for reflective method calls");
		System.out.println("\t--SEQUENTIALPATHPROCESSING Process all taint paths sequentially");
		System.out.println("\t--SINGLEJOINPOINTABSTRACTION Only record one source per join point");
		System.out.println("\t--NOCALLBACKSOURCES Don't treat parameters of callback methods as sources");
		System.out.println();
		System.out.println("Supported callgraph algorithms: AUTO, CHA, RTA, VTA, SPARK, GEOM");
		System.out.println("Supported layout mode algorithms: NONE, PWD, ALL");
		System.out.println("Supported path algorithms: CONTEXTSENSITIVE, CONTEXTINSENSITIVE, SOURCESONLY");
		System.out.println("Supported callback algorithms: DEFAULT, FAST");
		System.out.println("Supported alias algorithms: NONE, PTSBASED, FLOWSENSITIVE, LAZY");
		System.out.println("Supported code elimination modes: NONE, PROPAGATECONSTS, REMOVECODE");
	}
	
	private static void runAnalysisComparison(String fileName, String androidJar, Map<String, InfoflowAndroidConfiguration> runOptions) {
		
		String comparisonOutput = null;
		try (StringWriter sw = new StringWriter()) {
			if (runOptions.size() > 0) {
				//Gets the first configuration to use as a base line in compaarison
				Map.Entry<String, InfoflowAndroidConfiguration> entry = runOptions.entrySet().iterator().next();
				InfoflowAndroidConfiguration baseConfig = entry.getValue();
				InfoflowResultsData analysisResultsBase = runAnalysis(fileName, androidJar, baseConfig);
				
				sw.write("[" + entry.getKey() + "]\n");
				
				sw.write("number of flows:" + analysisResultsBase.results.numConnections() + "\n");
				if (reportAllFlows) {
					sw.write(analysisResultsBase.results.toString());
				}
				
				//Remove the first option
				runOptions.remove(entry.getKey());
				
				//Iterate through other options and run analysis
				for (String optionName : runOptions.keySet()) {
					InfoflowAndroidConfiguration optionConfig = runOptions.get(optionName);
					InfoflowResultsData analysisResultsOption = runAnalysis(fileName, androidJar, optionConfig);
					
					ResultComparison resultComparison = compareResults(analysisResultsBase, analysisResultsOption);
					
					sw.write("[" + optionName + "]\n");
					sw.write("number of flows:" + resultComparison.getComparisonFlows() + "\n");
					sw.write("matched flows:" + resultComparison.getNumMatched() + "\n");
					sw.write("missed flows:" + resultComparison.getNumMissed() + "\n");
					sw.write("false positive flows:" + resultComparison.getNumFalsePositive() + "\n");
					if (reportAllFlows) {
						sw.write(resultComparison.printComparisonFull());
					}
				}
				
				System.out.println(sw.toString());
				comparisonOutput = sw.toString();
				
				sw.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
			System.err.println("Error when printing comparison results: " + e);
		}
		
		if (comparisonOutput != null && logFilePath != null) {
			try (BufferedWriter bw = new BufferedWriter(new FileWriter(logFilePath))) {
				bw.write(comparisonOutput);
				bw.close();
			} catch (IOException e) {
				e.printStackTrace();
				System.err.println("Error when logging comparison results: " + e);
			}
		}
	}
	
	private static void determineOption(String fileName, String androidJar) {
		String comparisonOutput = null;
		try (StringWriter sw = new StringWriter()) {
			Map<Integer ,InfoflowAndroidConfigurationArgument> configurations = getConfigurationList();
			//long allocatedMemory = Runtime.getRuntime().maxMemory();
			
			for (int currConfigNum : configurations.keySet()) {
				InfoflowAndroidConfiguration currConfig = configurations.get(currConfigNum).config;
				ApplicationProperties appProperties = getApplicationProperties(fileName, androidJar, currConfig);
				int predictedSettingApp = predictSetting(appProperties);
				//int predictedMemoryProfile = predictSettingMemory(appProperties);
				
				if (currConfigNum >= predictedSettingApp) {
					sw.write("Predicted FlowDroid argument to use:\n");
					
					if (configurations.get(currConfigNum).arguments == "") {
						sw.write("[no additional arguments]");
					} else {
						sw.write(configurations.get(currConfigNum).arguments);
					}
					
					break;
				}
			}
			
			System.out.println(sw.toString());
			comparisonOutput = sw.toString();
			sw.close();
		} catch (IOException e) {
			e.printStackTrace();
			System.err.println("Error when printing prediction results: " + e);
		}
		
		if (comparisonOutput != null && logFilePath != null) {
			try (BufferedWriter bw = new BufferedWriter(new FileWriter(logFilePath))) {
				bw.write(comparisonOutput);
				bw.close();
			} catch (IOException e) {
				e.printStackTrace();
				System.err.println("Error when logging comparison results: " + e);
			}
		}
		
	}
	
	private static int currentMemoryProfile(long freeMemory) {
		if (freeMemory < 4294967296L) {
			return 0;
		} else if (freeMemory < 8589934592L) {
			return 1;
		} else if (freeMemory < 17179869184L) {
			return 2;
		} else if (freeMemory < 34359738368L) {
			return 3;
		} else if (freeMemory < 68719476736L) {
			return 4;
		}
		return 5;
	}
	
	private static Map<Integer , InfoflowAndroidConfigurationArgument> getConfigurationList() {
		Map<Integer ,InfoflowAndroidConfigurationArgument> configurations = new HashMap<Integer, InfoflowAndroidConfigurationArgument>();
		ArrayList<String> configArguments = new ArrayList<String>();
		//6 configurations in total
		configArguments.add("");
		configArguments.add("--nocallbacks");
		configArguments.add("--nocallbacks --nostatic --aplength 4");
		configArguments.add("--nocallbacks --nostatic --aliasflowins --noexceptions --aplength 3");
		configArguments.add("--nocallbacks --nostatic --aliasflowins --noexceptions --nopaths --noarraysize --aplength 2");
		configArguments.add("--nocallbacks --nostatic --aliasflowins --noexceptions --notypechecking --callbackanalyzer fast --nopaths --noarraysize --aplength 1");
		
		int configIndex = 1;
		for (String currConfig : configArguments) {
			configurations.put(configIndex, (new InfoflowAndroidConfigurationArgument(currConfig, parseAdditionalOptions(currConfig.split(" ")))));
			configIndex ++;
		}
		
		return configurations;
	}
	
	private static void runAnalysisGetProperties(String fileName, String androidJar, String optionName, InfoflowAndroidConfiguration option) {
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(fileName + "_" + optionName + ".properties"))) {
			//Runs analysis with no additional options enabled
			ApplicationProperties appProperties = getApplicationProperties(fileName, androidJar, option);
			
			bw.write(optionName + "\n");
			
			bw.write("callgraphedges:" + appProperties.getCallGraphEdges() + "\n");
			bw.write("sinks:" + appProperties.getNumSinks() + "\n");
			bw.write("sources:" + appProperties.getNumSources() + "\n");
			bw.write("entrypoints:" + appProperties.getNumEntrypoints() + "\n");
			bw.write("reachablemethods:" + appProperties.getNumReachableMethods() + "\n");
			bw.write("numclasses:" + appProperties.getNumClasses() + "\n");
			bw.write("providers:" + appProperties.getProviders() + "\n");
			bw.write("services:" + appProperties.getServices() + "\n");
			bw.write("activities:" + appProperties.getActivities() + "\n");
			bw.write("receivers:" + appProperties.getActivities() + "\n");
			bw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(fileName + "_" + optionName + ".propertiescomplete"))) {
			//Runs analysis with no additional options enabled
			InfoflowResultsData analysisResults = runAnalysis(fileName, androidJar, option);
			
			bw.write(optionName + "\n");
			bw.write("numberflows:" + analysisResults.results.numConnections() + "\n");
			bw.write("ram usage:" + analysisResults.getMaxMemoryConsumption() + "\n");
			bw.write("time:" + analysisResults.getRunTime() + "\n");
			bw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private static ResultComparison compareResults(InfoflowResultsData baseResultsData, InfoflowResultsData optionsResultsData) {
		InfoflowResults baseResults = baseResultsData.results;
		InfoflowResults optionsResults = optionsResultsData.results;
		
		MultiMap<ResultSinkInfo, ResultSourceInfo> baseResultsMap = removeDuplicates(baseResults.getResults());
		MultiMap<ResultSinkInfo, ResultSourceInfo> optionsResultsMap = removeDuplicates(optionsResults.getResults());
		
		//Map containing ...
		MultiMap<ResultSinkInfo, ResultSourceInfo> missedFlowMap = new HashMultiMap<ResultSinkInfo, ResultSourceInfo>();
		MultiMap<ResultSinkInfo, ResultSourceInfo> falsePositiveFlowMap = new HashMultiMap<ResultSinkInfo, ResultSourceInfo>();
		MultiMap<ResultSinkInfo, ResultSourceInfo> matchedFlowMap = new HashMultiMap<ResultSinkInfo, ResultSourceInfo>();
		
		ArrayList<ResultSinkInfo> missedSinks = new ArrayList<ResultSinkInfo>();
		ArrayList<ResultSinkInfo> falsePositiveSinks = new ArrayList<ResultSinkInfo>();
		ArrayList<ResultSinkInfo> matchedSinksBase = new ArrayList<ResultSinkInfo>(); 
		ArrayList<ResultSinkInfo> matchedSinksOptions = new ArrayList<ResultSinkInfo>(); 
		
		ResultComparison resultDiff = new ResultComparison(baseResults.numConnections(), optionsResults.numConnections());
		
		//Adds all the base sinks to a list to determine missed sinks
		for (ResultSinkInfo baseSink : baseResultsMap.keySet()) {
			missedSinks.add(baseSink);
		}
		
		//Adds all the base sinks to a list to determine false positives (sinks)
		for (ResultSinkInfo optionsSink : optionsResultsMap.keySet()) {
			falsePositiveSinks.add(optionsSink);
		}
		
		
		for (ResultSinkInfo baseSink : baseResultsMap.keySet()) {
			for (ResultSinkInfo optionsSink : optionsResultsMap.keySet()) {
				if (baseSink.toString().equals(optionsSink.toString())) {
					//Uses separate lists because sink objects themselves cannot be compared directly
					matchedSinksBase.add(baseSink);
					matchedSinksOptions.add(optionsSink);
					
					//Removes sinks from individual lists of sinks for each result
					//This is to determine potential missed flows or false positives
					missedSinks.remove(baseSink);
					falsePositiveSinks.remove(optionsSink);
				}
			}
		}
		
		//Comparing sources for sinks that match between results
		//For any missed sinks, the reported flows within them from any source
		//is automatically determined as a missed source (for a missed sink)
		for (ResultSinkInfo baseSink : missedSinks) {
			for (ResultSourceInfo sourceForSink : baseResultsMap.get(baseSink)) {
				missedFlowMap.put(baseSink, sourceForSink);
			}
		}
		
		//Similarly, for any potential false positives, add any source
		//for the flow to that sink as into the false positive map
		for (ResultSinkInfo optionsSink : falsePositiveSinks) {
			for (ResultSourceInfo sourceForSink : optionsResultsMap.get(optionsSink)) {
				falsePositiveFlowMap.put(optionsSink, sourceForSink);
			}
		}
		
		for (int i = 0; i < matchedSinksBase.size(); i++) {
			ResultSinkInfo currBaseSink = matchedSinksBase.get(i);
			ResultSinkInfo currOptionsSink = matchedSinksOptions.get(i);
			
			List<ResultSourceInfo> currSinkMissedSources = new ArrayList<ResultSourceInfo>();
			List<ResultSourceInfo> currSinkFalsePositiveSources = new ArrayList<ResultSourceInfo>();
			
			
			for (ResultSourceInfo baseSourceForSink : baseResultsMap.get(currBaseSink)) {
				currSinkMissedSources.add(baseSourceForSink);
			}
			
			//Adds all the base sinks to a list to determine false positives (sinks)
			for (ResultSourceInfo optionsSourceForSink : optionsResultsMap.get(currOptionsSink)) {
				currSinkFalsePositiveSources.add(optionsSourceForSink);
			}
			
			for (ResultSourceInfo baseSourceForSink : baseResultsMap.get(currBaseSink)) {
				for (ResultSourceInfo optionsSourceForSink : optionsResultsMap.get(currOptionsSink)) {
					if (currSinkFalsePositiveSources.contains(optionsSourceForSink)) {
						if (baseSourceForSink.toString().equals(optionsSourceForSink.toString())) {
							//Adds the base sink / source pair as a flow
							matchedFlowMap.put(currBaseSink, baseSourceForSink);
							
							currSinkMissedSources.remove(baseSourceForSink);
							currSinkFalsePositiveSources.remove(optionsSourceForSink);
							break;
						}
					}
				}
			}
			
			for (ResultSourceInfo baseSourceForSink : currSinkMissedSources) {
				missedFlowMap.put(currBaseSink, baseSourceForSink);
			}
			
			for (ResultSourceInfo optionsSourceForSink : currSinkFalsePositiveSources) {
				falsePositiveFlowMap.put(currOptionsSink, optionsSourceForSink);
			}
		}
		
		resultDiff.setResult(missedFlowMap, falsePositiveFlowMap, matchedFlowMap);
		return resultDiff;
	}
	
	private static Map<String, InfoflowAndroidConfiguration> setupRunOptions(String optionsList) {
		Map<String, InfoflowAndroidConfiguration> options = new LinkedHashMap<String, InfoflowAndroidConfiguration>();
		
		try {
			BufferedReader br = new BufferedReader(new FileReader(optionsList));		
			String tagLine = br.readLine();
			String optionsLine = br.readLine();
			while (tagLine != null && optionsLine != null) {
				String[] optionsArray = optionsLine.split("\\s+");
				
				InfoflowAndroidConfiguration optionsConfig = parseAdditionalOptions(optionsArray);
				if (optionsConfig != null) {
					options.put(tagLine, optionsConfig);
				}
				
				tagLine = br.readLine();
				optionsLine = br.readLine();
			}
			br.close();
		} catch(Exception e) {
			System.err.println("Error in options reading: " + e);
		}
		
		return options;
	}
	
	//Algorithm to remove duplicate sinks from analysis results
	private static MultiMap<ResultSinkInfo, ResultSourceInfo> removeDuplicates(MultiMap<ResultSinkInfo, ResultSourceInfo> results) {
		MultiMap<ResultSinkInfo, ResultSourceInfo> resultsMap = new HashMultiMap<ResultSinkInfo, ResultSourceInfo>();
		
		ResultSinkInfo[] resultSinks = results.keySet().toArray(new ResultSinkInfo[results.keySet().size()]);
		
		for (int i = 0; i < resultSinks.length; i++) {
			//Creates a new set to store sources
			if (resultSinks[i] != null) {
				Set<ResultSourceInfo> resultSinkSources = new HashSet<ResultSourceInfo>(); 
				resultSinkSources.addAll(results.get(resultSinks[i]));
				
				for (int j = i + 1; j < resultSinks.length; j++) {
					if (resultSinks[j] != null) {
						//Cannot compare ResultSourceInfo objects directly, due to some objects containing
						//the same value, but evaluate as different objects in .equals() comparison.
						if (resultSinks[i].toString().equals(resultSinks[j].toString())) {
							//If a duplicate sink is found, add their list of sources to the current list of sources

							resultSinkSources.addAll(results.get(resultSinks[j]));
							resultSinks[j] = null;
						}
					}
				}
				
				resultsMap.putAll(resultSinks[i], resultSinkSources);
			}
		}
		
		return resultsMap;
	}
	
	private static int predictSetting(ApplicationProperties properties) {
		String SVM_MODEL_FILE = "flowdroid_model_app.txt";
		try {
			svm_model model = svm.svm_load_model(SVM_MODEL_FILE);
			
			int numTokens = 10;
			svm_node[] x = new svm_node[numTokens];
			for (int i = 0; i < numTokens; i++) {
				x[i] = new svm_node();
				x[i].index = i;
			}
			
			x[0].value = properties.getCallGraphEdges();
			x[1].value = properties.getNumSources();
			x[2].value = properties.getNumSinks();
			x[3].value = properties.getNumEntrypoints();
			x[4].value = properties.getNumReachableMethods();
			x[5].value = properties.getNumClasses();
			x[6].value = properties.getProviders();
			x[7].value = properties.getServices();
			x[8].value = properties.getActivities();
			x[9].value = properties.getReceivers();

			double v = svm.svm_predict(model, x);
			return (int) v;
		} catch (Exception e) {
			System.out.println("Error: " + e);
		}
		return -1;
	}
	
	private static int predictSettingMemory(ApplicationProperties properties) {
		String SVM_MODEL_FILE = "flowdroid_model_memory.txt";
		try {
			svm_model model = svm.svm_load_model(SVM_MODEL_FILE);
			
			int numTokens = 10;
			svm_node[] x = new svm_node[numTokens];
			for (int i = 0; i < numTokens; i++) {
				x[i] = new svm_node();
				x[i].index = i;
			}
			
			x[0].value = properties.getCallGraphEdges();
			x[1].value = properties.getNumSources();
			x[2].value = properties.getNumSinks();
			x[3].value = properties.getNumEntrypoints();
			x[4].value = properties.getNumReachableMethods();
			x[5].value = properties.getNumClasses();
			x[6].value = properties.getProviders();
			x[7].value = properties.getServices();
			x[8].value = properties.getActivities();
			x[9].value = properties.getReceivers();

			double v = svm.svm_predict(model, x);
			return (int) v;
		} catch (Exception e) {
			System.out.println("Error: " + e);
		}
		return -1;
	}
	
}
