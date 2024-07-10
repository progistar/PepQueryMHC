package progistar.scan.data;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;

import progistar.scan.function.Random;
import progistar.scan.function.Translator;
import progistar.scan.function.Utils;
import progistar.scan.run.Scan;
import progistar.scan.run.Task;

public class ParseRecord {

	private ParseRecord() {}

	/**
	 * id fr_sequence
	 * id ACGTGGAGT
	 * 
	 * 
	 * @param file
	 * @return
	 * @throws IOException
	 */
	public static ArrayList<SequenceRecord> parse (File file) throws IOException {
		ArrayList<SequenceRecord> records = new ArrayList<SequenceRecord>();
		BufferedReader BR = new BufferedReader(new FileReader(file));
		Hashtable<String, SequenceRecord> indexedRecords = new Hashtable<String, SequenceRecord>();
		String line = null;
		
		SequenceRecord.header = BR.readLine();
		SequenceRecord.fileName = Scan.bamFile.getName();
		
		String[] headerSplit = SequenceRecord.header.split("\t");
		int obsSeqIdx = -1;
		int genomicLociIdx = -1;
		int strandIdx = -1;
		
		for(int i=0; i<headerSplit.length; i++) {
			if(Scan.sequence.equalsIgnoreCase(Constants.SEQUENCE_NUCLEOTIDE) && 
					headerSplit[i].equalsIgnoreCase("sequence")) {
				obsSeqIdx = i;
			} else if(Scan.sequence.equalsIgnoreCase(Constants.SEQUENCE_PEPTIDE) && 
					headerSplit[i].equalsIgnoreCase("sequence")) {
				obsSeqIdx = i;
			} else if(headerSplit[i].equalsIgnoreCase("location")) {
				genomicLociIdx = i;
			} else if(headerSplit[i].equalsIgnoreCase("strand")) {
				strandIdx = i;
			} 
		}
		
		if(Scan.mode.equalsIgnoreCase(Constants.MODE_TARGET)) {

			while((line = BR.readLine()) != null) {
				String[] fields = line.split("\t");
				String sequence = fields[obsSeqIdx];
				String genomicLoci = fields[genomicLociIdx];
				String strand = fields[strandIdx];
				
				SequenceRecord record = new SequenceRecord();
				record.sequence = sequence;
				record.strand = strand;
				record.location = genomicLoci;
				
				if(Scan.sequence.equalsIgnoreCase(Constants.SEQUENCE_NUCLEOTIDE)) {
					if(strand.charAt(0) == '-') {
						// rc sequence of nucleotide
						record.sequence = Translator.getReverseComplement(record.sequence);
					}
				}
				
				String chr = Constants.NULL;
				int start = -1;
				int end = -1;
				
				if(!genomicLoci.equalsIgnoreCase(Constants.NULL)) {
					String[] gLoc = genomicLoci.split("\\|");
					for(String gLocus : gLoc) {
						chr = gLocus.split("\\:")[0];
						if(start == -1) {
							start = Integer.parseInt(gLocus.split("\\:")[1].split("\\-")[0]);
						}
						end = Integer.parseInt(gLocus.split("\\:")[1].split("\\-")[1]);
					}
					
					if(chr.equalsIgnoreCase("chrx")) {
						chr = "chrX";
					} else if(chr.equalsIgnoreCase("chry")) {
						chr = "chrY";
					} else if(chr.equalsIgnoreCase("chrm")) {
						chr = "chrM";
					} else if(!chr.startsWith("chr")) {
						chr = chr.toUpperCase();
					}
				}
				
				record.chr = chr;
				record.start = start;
				record.end = end;
				
				String key = record.getKey();
				
				SequenceRecord indexedRecord = indexedRecords.get(key);
				if(indexedRecord == null) {
					indexedRecord = record;
					indexedRecords.put(key, indexedRecord);
					records.add(indexedRecord);
				}
				indexedRecord.records.add(line);
			}
		} else {
			while((line = BR.readLine()) != null) {
				String[] fields = line.split("\t");
				String sequence = fields[obsSeqIdx];
				
				SequenceRecord record = new SequenceRecord();
				record.sequence = Scan.isILEqual ? sequence.replace("I", "L") : sequence;
				record.strand = Constants.NULL;
				record.location = Constants.NULL;
				
				String key = record.getKey();
				
				SequenceRecord indexedRecord = indexedRecords.get(key);
				if(indexedRecord == null) {
					indexedRecord = record;
					indexedRecords.put(key, indexedRecord);
					records.add(indexedRecord);
				}
				indexedRecord.records.add(line);
			}
			
			if(Scan.isRandom) {
				indexedRecords.forEach((key, record)->{
					SequenceRecord rRecord = new SequenceRecord();
					rRecord.sequence = Random.getReverseSequence(record.sequence);
					rRecord.strand = Constants.NULL;
					rRecord.location = Constants.NULL;
					rRecord.isRandom = true;
					
					if(indexedRecords.get(rRecord.getKey()) == null) {
						records.add(rRecord);
					}
				});
				
				int numOfRandomSequences = 0;
				for(SequenceRecord record : records) {
					if(record.isRandom) {
						numOfRandomSequences ++;
					}
				}
				System.out.println("The number of "+numOfRandomSequences+" random sequences were generated.");
			}
		}
		
		
		BR.close();
		return records;
	}
	
	/**
	 * For target mode
	 * 
	 * @param records
	 * @param file
	 * @throws IOException
	 */
	public static void writeRecords (ArrayList<SequenceRecord> records, File file) throws IOException {
		writeLibSize(file);
		
		BufferedWriter BW = new BufferedWriter(new FileWriter(file));
		BufferedWriter BWPeptCount = new BufferedWriter(new FileWriter(file.getAbsolutePath()+".pept_count.tsv"));
		
		// write header
		BW.append(SequenceRecord.header+"\tReadCount\tRPHM");
		BW.newLine();
		BWPeptCount.append("Sequence\tReadCount\tRPHM");
		BWPeptCount.newLine();
		
		Hashtable<String, Long> readCountsPeptLevel = new Hashtable<String, Long>();
		
		// write records
		for(int i=0; i<records.size(); i++) {
			SequenceRecord record = records.get(i);
			
			long readCnt = record.readCnt;
			for(int j=0; j<record.records.size(); j++) {
				BW.append(record.records.get(j)).append("\t"+readCnt+"\t"+Utils.getRPHM((double)readCnt));
				BW.newLine();
			}
			
			Long sumReads = readCountsPeptLevel.get(record.sequence);
			if(sumReads == null) {
				sumReads = 0L;
			}
			sumReads += readCnt;
			readCountsPeptLevel.put(record.sequence, sumReads);
		}
		
		readCountsPeptLevel.forEach((sequence, reads)->{
			try {
				BWPeptCount.append(sequence+"\t"+reads+"\t"+Utils.getRPHM((double)reads));
				BWPeptCount.newLine();
			}catch(IOException ioe) {
				
			}
 		});
		
		BW.close();
		BWPeptCount.close();
	}
	
	/**
	 * For scan mode
	 * 
	 * @param records
	 * @param file
	 * @param tasks
	 * @throws IOException
	 */
	public static void writeRecords (ArrayList<SequenceRecord> records, File file, ArrayList<Task> tasks) throws IOException {
		writeLibSize(file);
		
		BufferedWriter BW = new BufferedWriter(new FileWriter(file));
		BufferedWriter BWGenomicTuple = new BufferedWriter(new FileWriter(file.getAbsolutePath()+".gloc.tsv"));
		BufferedWriter BWNotFound = new BufferedWriter(new FileWriter(file.getAbsolutePath()+".not_found.tsv"));
		BufferedWriter BWPeptCount = new BufferedWriter(new FileWriter(file.getAbsolutePath()+".pept_count.tsv"));
		
		LocTable locTable = new LocTable();
		
		// union information
		for(Task task : tasks) {
			task.locTable.table.forEach((sequence, lInfos) -> {
				lInfos.forEach((key, lInfo)->{
					locTable.putLocation(lInfo);
				});
			});
		}
		
		// write header
		BW.append(SequenceRecord.header+"\tLocation\tMutations\tStrand\tObsNucleotide\tObsPeptide\tRefNucleotide\tReadCount\tRPHM");
		BW.newLine();
		BWGenomicTuple.append("ObsPeptide\tLocation\tStrand\tReadCount\tPRHM");
		BWGenomicTuple.newLine();
		
		BWNotFound.append(SequenceRecord.header+"\tLocation");
		BWNotFound.newLine();
		BWPeptCount.append("ObsPeptide\tReadCount\tRPHM\tNumLocations");
		BWPeptCount.newLine();
		
		
		// write records
		// unique observed sequence.
		Hashtable<String, Long> readCountsPeptLevel = new Hashtable<String, Long>();
		Hashtable<String, Integer> locationsPeptLevel = new Hashtable<String, Integer>();
		
		Hashtable<String, Long> readCountsRandomPeptLevel = new Hashtable<String, Long>();
		Hashtable<String, Integer> locationsRandomPeptLevel = new Hashtable<String, Integer>();
		
		Hashtable<String, Long> readCountsTupleLevel = new Hashtable<String, Long>();
		Hashtable<String, Boolean> isUniqueCal = new Hashtable<String, Boolean>();
		
		for(int i=0; i<records.size(); i++) {
			SequenceRecord record = records.get(i);
			String sequence = record.sequence;
			ArrayList<LocationInformation> locations = locTable.getLocations(sequence);
			
			// the records must be an unique item!
			if(isUniqueCal.get(sequence) != null) {
				System.err.println("Severe: the records is not unique!");
			}
			isUniqueCal.put(sequence, true);
			
			for(LocationInformation location : locations) {
				long readCount = location.readCount;
				// it must be calculated once!
				// peptide level count
				
				// random count
				if(record.isRandom) {
					Long sumReads = readCountsRandomPeptLevel.get(location.obsPeptide);
					if(sumReads == null) {
						sumReads = 0L;
					}
					sumReads += readCount;
					readCountsRandomPeptLevel.put(location.obsPeptide, sumReads);
					locationsRandomPeptLevel.put(location.obsPeptide, locations.size());
				} 
				// non-random count
				else {
					Long sumReads = readCountsPeptLevel.get(location.obsPeptide);
					if(sumReads == null) {
						sumReads = 0L;
					}
					sumReads += readCount;
					readCountsPeptLevel.put(location.obsPeptide, sumReads);
					locationsPeptLevel.put(location.obsPeptide, locations.size());
					
					// tuple level count
					String tupleKey = location.obsPeptide+"\t"+location.location+"\t"+location.strand;
					sumReads = readCountsTupleLevel.get(tupleKey);
					if(sumReads == null) {
						sumReads = 0L;
					}
					sumReads += readCount;
					readCountsTupleLevel.put(tupleKey, sumReads);
				}
			}
			
			
			
			// if there are duplicated records, then this size > 1
			// if there is no duplication, then this size = 1
			
			// pass random sequence
			// random sequences are only written in the peptide count.
			if(!record.isRandom) {
				for(int j=0; j<record.records.size(); j++) {
					if(locations.size() == 0) {
						BWNotFound.append(record.records.get(j)).append("\tNot found");
						BWNotFound.newLine();
					} else {
						for(LocationInformation location : locations) {
							// full information (including genomic sequence)
							BW.append(record.records.get(j)).append("\t"+location.getRes());
							BW.newLine();
						}
					}
				}
			}
			
		}
		BWNotFound.close();
		BW.close();
		
		readCountsPeptLevel.forEach((sequence, reads)->{
			try {
				BWPeptCount.append(sequence+"\t"+reads+"\t"+Utils.getRPHM((double)reads)+"\t"+locationsPeptLevel.get(sequence));
				BWPeptCount.newLine();
			}catch(IOException ioe) {
				
			}
 		});
		
		BWPeptCount.close();
		
		readCountsTupleLevel.forEach((tupleKey, reads)->{
			try {
				BWGenomicTuple.append(tupleKey+"\t"+reads+"\t"+Utils.getRPHM((double)reads));
				BWGenomicTuple.newLine();
			}catch(IOException ioe) {
				
			}
 		});
		BWGenomicTuple.close();
		
		
		
		// if calculate random distribution is on :
		if(Scan.isRandom) {
			BufferedWriter BWRandomPeptCount = new BufferedWriter(new FileWriter(file.getAbsolutePath()+".pept_count.random.tsv"));
			BWRandomPeptCount.append("rObsPeptide\tReadCount\tRPHM\tNumLocations");
			BWRandomPeptCount.newLine();
			readCountsRandomPeptLevel.forEach((sequence, reads)->{
				try {
					BWRandomPeptCount.append(sequence+"\t"+reads+"\t"+Utils.getRPHM((double)reads)+"\t"+locationsRandomPeptLevel.get(sequence));
					BWRandomPeptCount.newLine();
				}catch(IOException ioe) {
					
				}
	 		});
			
			BWRandomPeptCount.close();
			
		}
	}
	
	private static void writeLibSize (File file) throws IOException {
		BufferedWriter BW = new BufferedWriter(new FileWriter(file.getAbsolutePath()+".libsize"));
		BW.append(Scan.libSize+"");
		BW.close();
	}
}
