package progistar.scan.run;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.util.Log;
import progistar.scan.data.BarcodeTable;
import progistar.scan.data.Codon;
import progistar.scan.data.Constants;
import progistar.scan.data.LocTable;
import progistar.scan.data.ParseRecord;
import progistar.scan.data.Phred;
import progistar.scan.data.SequenceRecord;
import progistar.scan.function.CheckMemory;

public class Scan {

	//-i test/benchmark/test.tsv -b test/benchmark/test.bam -m scan -s nucleotide -o test/benchmark/test.scan -@ 1
	//-i test/benchmark/test.tsv -b test/benchmark/C3N-02145.T.Aligned.sortedByCoord.out.bam -m scan -s nucleotide -o test/benchmark/test.scan -@ 4
	//-i test/benchmark/target_test.tsv -b test/benchmark/C3N-02145.T.Aligned.sortedByCoord.out.bam -m target -s peptide -o test/benchmark/target_test.scan -@ 4
	//-i test/benchmark/C3N_02145_T_nonreference.tsv -b test/benchmark/C3N-02145.T.Aligned.sortedByCoord.out.bam -m scan -s peptide -o test/benchmark/C3N_02145_T_nonreference.scan -@ 4
	
	//-i test/benchmark/C3N_02145_T_nonreference.tsv -b test/benchmark/C3N-02145.T.Aligned.sortedByCoord.out.bam -m target -s peptide -o test/benchmark/C3N_02145_T_nonreference.scan -@ 4
	
	
	
	public static File inputFile = null;
	public static File bamFile = null;
	public static File whitelistFile = null;
	public static String outputBaseFilePath	= null;
	public static String mode	=	Constants.MODE_TARGET;
	public static String sequence	=	Constants.SEQUENCE_PEPTIDE;
	public static String count	=	Constants.COUNT_PRIMARY;
	public static String union	=	Constants.UNION_SUM;
	public static String strandedness = Constants.AUTO_STRANDED;
	public static double libSize = 0;
	
	public static boolean isILEqual = false;
	public static boolean isSingleCellMode = false;
	public static boolean verbose = false;
	public static int threadNum = 4;
	public static int chunkSize = 100;
	
	
	public static int longestSequenceLen = -1;
	
	// read quality control ///////////////////
	/**
	 * single base cutoff
	 * @deprecated
	 */
	public static int singleBaseThreshold = 20;
	
	/**
	 * ROI base cutoff
	 */
	public static double ROIErrorThreshold = 0.05;
	///////////////////////////////////////////
	
	public static String unmmapedMarker = null;
	
	
	public static void main(String[] args) throws IOException, InterruptedException {
		// performance metrics //
		long startTime = System.currentTimeMillis();
		long peakMemory = CheckMemory.checkUsedMemoryMB();
		/////////////////////////
		
		printDescription(args);
		parseOptions(args);
		Codon.mapping();
		Phred.loadTable(); // load phred table
		// single cell barcode
		if(isSingleCellMode) {
			BarcodeTable.load();
		}
		
		ArrayList<SequenceRecord> records = ParseRecord.parse(inputFile);
		
		//// Prepare tasks
		ArrayList<Task> tasks = new ArrayList<Task>();
		
		// auto strand detection
		if(strandedness.equalsIgnoreCase(Constants.AUTO_STRANDED)) {
			tasks.addAll(Task.getStrandDetectionTask());
			ExecutorService executorService = Executors.newFixedThreadPool(threadNum);
			List<Worker> callableExList = new ArrayList<>();
			for(int i=0; i<tasks.size(); i++) {
				Task task = tasks.get(i);
				callableExList.add(new Worker(task));
			}
			
			// check peak memory
			peakMemory = Math.max(peakMemory, CheckMemory.checkUsedMemoryMB());
			
			executorService.invokeAll(callableExList);
			executorService.shutdown();
			
			int R1F = 0;
			int R1R = 0;
			int R2F = 0;
			int R2R = 0;
			
			for(Task task :tasks) {
				R1F += task.R1F;
				R1R += task.R1R;
				R2F += task.R2F;
				R2R += task.R2R;
			}
			
			if(R1F*10 < R1R && R2F > R2R*10) {
				strandedness = Constants.RF_STRANDED;
			} else if(R1F > R1R*10 && R2F*10 < R2R) {
				strandedness = Constants.FR_STRANDED;
			} else {
				strandedness = Constants.NON_STRANDED;
			}
			System.out.println("Estimate strandedness");
			System.out.println("1F\t1R\t2F\t2R");
			System.out.println(R1F+"\t"+R1R+"\t"+R2F+"\t"+R2R);
			
			if(R1F+R1R+R2F+R2R == 0) {
				System.out.println("Fail to estimate stradedness!");
				System.out.println("It looks single-read RNA-seq experiement. Please specify strandedness.");
				System.exit(1);
			} else {
				System.out.println("Strandedness: "+strandedness+"-stranded");
			}
			
			tasks.clear();
		}
		/////////////////////////////////////////////////////////////////
		
		// core algorithm
		if(mode.equalsIgnoreCase(Constants.MODE_TARGET)) {
			// estimate library size
			if(libSize == 0) {
				tasks.addAll(Task.getLibSizeTask());
			}
			// target mode
			chunkSize = (records.size() / (10 * threadNum) ) +1;
			tasks.addAll(Task.getTargetModeTasks(records, chunkSize));
		} else if(mode.equalsIgnoreCase(Constants.MODE_SCAN)) {
			tasks.addAll(Task.getScanModeTasks(records));
		}
		//// sort tasks by descending order
		// Priority: Library > Unmapped > Mapped
		Collections.sort(tasks);
		
		//// Enroll tasks on a thread pool
		ExecutorService executorService = Executors.newFixedThreadPool(threadNum);
		List<Worker> callableExList = new ArrayList<>();
		for(int i=0; i<tasks.size(); i++) {
			Task task = tasks.get(i);
			callableExList.add(new Worker(task));
		}
		
		// check peak memory
		peakMemory = Math.max(peakMemory, CheckMemory.checkUsedMemoryMB());
		
		executorService.invokeAll(callableExList);
		executorService.shutdown();
		//// End of tasks
		
		System.out.println("Done all tasks!");
		// check peak memory
		peakMemory = Math.max(peakMemory, CheckMemory.checkUsedMemoryMB());

		// update peak memory from the tasks.
		for(Task task : tasks) {
			peakMemory = Math.max(peakMemory, task.peakMemory);
		}
		
		// calculate library size
		if(libSize == 0) {
			for(Task task : tasks) {
				libSize += task.processedReads;
			}
		}
		
		// make location table
		LocTable locTable = new LocTable();
		
		// union information
		for(Task task : tasks) {
			task.locTable.table.forEach((sequence, lInfos) -> {
				lInfos.forEach((key, lInfo)->{
					locTable.putLocation(lInfo);
				});
			});
		}
		
		ParseRecord.writeMainOutput(records, outputBaseFilePath, locTable);
		ParseRecord.writeLocationLevelOutput(records, outputBaseFilePath, locTable);
		ParseRecord.writePeptideLevelOutput(records, outputBaseFilePath, locTable);
		
		// check peak memory
		peakMemory = Math.max(peakMemory, CheckMemory.checkUsedMemoryMB());
		
		long endTime = System.currentTimeMillis();
		System.out.println("Library size: "+libSize);
		System.out.println("Total Elapsed Time: "+(endTime-startTime)/1000+" sec");
		System.out.println("Estimated Peak Memory: "+peakMemory +" MB");
	}
	
	
	
	/**
	 * Parse and apply arguments
	 * 
	 * @param args
	 */
	public static void parseOptions (String[] args) {
		
		CommandLine cmd = null;
		Options options = new Options();
		
		// Mandatory
		Option optionInput = Option.builder("i")
				.longOpt("input").argName("file path")
				.hasArg()
				.required(true)
				.desc("input path.")
				.build();
		
		Option optionBam = Option.builder("b")
				.longOpt("bam").argName("bam|sam")
				.hasArg()
				.required(true)
				.desc("bam or sam file.")
				.build();
		
		Option optionOutput = Option.builder("o")
				.longOpt("output").argName("file path")
				.hasArg()
				.required(true)
				.desc("output prefix path.")
				.build();
		
		Option optionMode = Option.builder("m")
				.longOpt("mode").argName("scan|target")
				.hasArg()
				.required(false)
				.desc("\"scan-mode\" counts all reads matching a given sequence by traversing all reads and annotates their genomic information. "
						+ "\n\"target-mode\" counts all reads matching a given sequence in a given genomic region."
						+ " \"both modes\" require .bai in advance.")
				.build();
		
		Option optionThread = Option.builder("@")
				.longOpt("thread").argName("int")
				.hasArg()
				.required(false)
				.desc("the number of threads.")
				.build();
		
		Option optionPrimary = Option.builder("c")
				.longOpt("count").argName("primary|all")
				.hasArg()
				.required(false)
				.desc("count only primary or all reads (default is primary).")
				.build();
		
		Option optionIL = Option.builder("e")
				.longOpt("equal").argName("")
				.required(false)
				.desc("consider that I is equivalent to L (only available in scan mode).")
				.build();
		
		Option optionLibSize = Option.builder("l")
				.longOpt("lib_size").argName("int")
				.hasArg()
				.required(false)
				.desc("library size to calculate RPHM value." +
						"\nif this option is not used, then it estimates the library size automatically. This estimation takes additional time for target mode.")
				.build();
		
		Option optionVerbose = Option.builder("v")
				.longOpt("verbose").argName("")
				.required(false)
				.desc("print every messages being processed.")
				.build();
		
		Option optionWhiteList = Option.builder("w")
				.longOpt("whitelist").argName("file path")
				.hasArg()
				.required(false)
				.desc("cell barcode list (tsv).")
				.build();
		
		Option optionROIThreshold = Option.builder("p")
				.longOpt("prob").argName("float (0,1]")
				.hasArg()
				.required(false)
				.desc("ignore ROIs (region of interests) with greater than a given error probability (default is 0.05).")
				.build();
		
		Option optionUnionPeptide = Option.builder("u")
				.longOpt("union").argName("sum|max")
				.hasArg()
				.required(false)
				.desc("calculate peptide level count by maximum or sum of the same peptide (default is sum).")
				.build();
		
		Option optionStrandeness = Option.builder("s")
				.longOpt("strand").argName("non|fr|rf|f|r|auto")
				.hasArg()
				.required(false)
				.desc("strand-specificity. non: non-stranded, fr: fr-second strand, rf: fr-first strand, f: forward strand for single-end, r: reverse strand for single-end, "
						+ "auto: auto-detection. Auto-detection is only available if there is XS tag in a given BAM file (default is auto).")
				.build();
		
		options.addOption(optionInput)
		.addOption(optionOutput)
		.addOption(optionMode)
		.addOption(optionStrandeness)
		.addOption(optionBam)
		.addOption(optionThread)
		.addOption(optionPrimary)
		.addOption(optionIL)
		.addOption(optionLibSize)
		.addOption(optionVerbose)
		.addOption(optionWhiteList)
		.addOption(optionROIThreshold)
		.addOption(optionUnionPeptide);
		
		CommandLineParser parser = new DefaultParser();
	    HelpFormatter helper = new HelpFormatter();
	    boolean isFail = false;
	    
		try {
		    cmd = parser.parse(options, args);
		    
		    if(cmd.hasOption("i")) {
		    	Scan.inputFile = new File(cmd.getOptionValue("i"));
		    }
		    
		    if(cmd.hasOption("b")) {
		    	Scan.bamFile = new File(cmd.getOptionValue("b"));
		    }
		    
		    if(cmd.hasOption("o")) {
		    	Scan.outputBaseFilePath = new File(cmd.getOptionValue("o")).getAbsolutePath();
		    }
		    
		    if(cmd.hasOption("m")) {
		    	Scan.mode = cmd.getOptionValue("m");
		    	
		    	if( !(mode.equalsIgnoreCase(Constants.MODE_SCAN) || 
		    			Scan.mode.equalsIgnoreCase(Constants.MODE_TARGET)) ) {
		    		isFail = true;
		    	}
		    }
		    
		    if(cmd.hasOption("s")) {
		    	Scan.strandedness = cmd.getOptionValue("s");
		    	// there is no matched option
		    	if(!Scan.strandedness.equalsIgnoreCase(Constants.AUTO_STRANDED) &&
		    		!Scan.strandedness.equalsIgnoreCase(Constants.FR_STRANDED) &&
		    		!Scan.strandedness.equalsIgnoreCase(Constants.RF_STRANDED) &&
		    		!Scan.strandedness.equalsIgnoreCase(Constants.F_STRANDED) &&
		    		!Scan.strandedness.equalsIgnoreCase(Constants.R_STRANDED) &&
		    		!Scan.strandedness.equalsIgnoreCase(Constants.NON_STRANDED) ) {
		    		System.out.println("Wrong strandedness: "+Scan.strandedness);
		    		isFail = true;
		    	}
		    }
		    
		    if(cmd.hasOption("e")) {
		    	Scan.isILEqual = true;
		    }
		    
		    if(cmd.hasOption("@")) {
		    	Scan.threadNum = Integer.parseInt(cmd.getOptionValue("@"));
		    }
		    
		    if(cmd.hasOption("c")) {
		    	Scan.count = cmd.getOptionValue("c");
		    	
		    	if(Scan.count.equalsIgnoreCase(Constants.COUNT_PRIMARY)) {
		    		Scan.count = Constants.COUNT_PRIMARY;
		    	} else {
		    		Scan.count = Constants.COUNT_ALL;
		    	}
		    }
		    
		    if(cmd.hasOption("v")) {
		    	Scan.verbose = true;
		    }
		    
		    if(cmd.hasOption("l")) {
		    	Scan.libSize = Double.parseDouble(cmd.getOptionValue("l"));
		    }
		    
		    if(cmd.hasOption("w")) {
		    	Scan.whitelistFile = new File(cmd.getOptionValue("w"));
		    	Scan.isSingleCellMode = true;
		    }
		    
		    if(cmd.hasOption("p")) {
		    	double roiCutoff = Double.parseDouble(cmd.getOptionValue("p"));
		    	if(Math.abs(roiCutoff) > 1 || roiCutoff == 0) {
		    		System.out.println("ROI cutoff is out of range (0,1]: "+roiCutoff);
		    		isFail = true;
		    	} else {
		    		Scan.ROIErrorThreshold = roiCutoff;
		    	}
		    	
		    }
		    
		    if(cmd.hasOption("u")) {
		    	// default is max.
		    	if(cmd.getOptionValue("u").equalsIgnoreCase("sum")) {
		    		Scan.union = Constants.UNION_SUM;
		    	}
		    }
		    
		} catch (ParseException e) {
			System.out.println(e.getMessage());
			isFail = true;
		}
		
		if(isFail) {
		    helper.printHelp("Usage:", options);
		    System.exit(0);
		} else {
			System.out.println("Input file name: "+Scan.inputFile.getAbsolutePath());
			System.out.println("BAM/SAM file name: "+Scan.bamFile.getAbsolutePath());
			System.out.println("Output file name: "+Scan.outputBaseFilePath);

			if(whitelistFile != null) {
				System.out.println("White-list file name: "+Scan.whitelistFile.getName() +" (single-cell mode)");
			}
			
			System.out.println("Strandedness: "+Scan.strandedness);
			System.out.println("Mode: "+Scan.mode);
			System.out.println("Count: "+Scan.count);
			System.out.println("Peptide level count: "+Scan.union);
			System.out.println("ROI cutoff: "+Scan.ROIErrorThreshold);
			System.out.println("Threads: "+Scan.threadNum);
			if(Scan.verbose) {
				System.out.println("Verbose messages");
			}
			if(Scan.isILEqual) {
				if(Scan.mode.equalsIgnoreCase(Constants.MODE_SCAN) && Scan.sequence.equalsIgnoreCase(Constants.SEQUENCE_PEPTIDE)) {
					System.out.println("I and L are equivalent!");
				} else {
					System.out.println("This is target mode or nucleotide input. IL option is ignored.");
					Scan.isILEqual = false;
				}
			}
		}
		System.out.println();
	}
	
	public static void printDescription (String[] args) {
		System.out.println(Constants.NAME+" "+Constants.VERSION+" ("+Constants.RELEASE+")");
		System.out.println("running date: " + java.time.LocalDate.now());
		StringBuilder optionStr = new StringBuilder();
		optionStr.append("command line: ");
		for(int i=0; i<args.length; i++) {
			if(i != 0) {
				optionStr.append(" ");
			}
			optionStr.append(args[i]);
		}
		System.out.println(optionStr.toString());
		System.out.println();
	}
}
