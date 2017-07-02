/**
* Copyright 2017 ZuInnoTe (Jörn Franke) <zuinnote@gmail.com>
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
**/
package org.zuinnote.hadoop.office.format.common.parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.poi.EmptyFileException;
import org.apache.poi.hssf.eventusermodel.HSSFEventFactory;
import org.apache.poi.hssf.eventusermodel.HSSFListener;
import org.apache.poi.hssf.eventusermodel.HSSFRequest;
import org.apache.poi.hssf.record.BOFRecord;
import org.apache.poi.hssf.record.BoundSheetRecord;
import org.apache.poi.hssf.record.ExtendedFormatRecord;
import org.apache.poi.hssf.record.FormatRecord;
import org.apache.poi.hssf.record.FormulaRecord;
import org.apache.poi.hssf.record.LabelSSTRecord;
import org.apache.poi.hssf.record.NumberRecord;
import org.apache.poi.hssf.record.Record;
import org.apache.poi.hssf.record.RowRecord;
import org.apache.poi.hssf.record.SSTRecord;
import org.apache.poi.hssf.record.StringRecord;
import org.apache.poi.hssf.record.chart.NumberFormatIndexRecord;
import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
import org.apache.poi.poifs.filesystem.DocumentFactoryHelper;
import org.apache.poi.poifs.filesystem.NPOIFSFileSystem;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.model.SharedStringsTable;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLReaderFactory;
import org.zuinnote.hadoop.office.format.common.HadoopOfficeReadConfiguration;
import org.zuinnote.hadoop.office.format.common.dao.SpreadSheetCellDAO;
import org.zuinnote.hadoop.office.format.common.util.MSExcelUtil;

/*
*
* This class is responsible for parsing Excel content in OOXML format and old excel format using a low resource footprint (CPU, memory)
*
*/
public class MSExcelLowFootprintParser implements OfficeReaderParserInterface  {
	/*
	* In the default case all sheets are parsed one after the other.
	* @param hocr HadoopOffice configuration for reading files:
	* locale to use (if null then default locale will be used), see java.util.Locale
	* filename Filename of the document
	* password Password of this document (null if no password)
	*
	*/
	public final static int FORMAT_UNSUPPORTED=-1;
	public final static int FORMAT_OLDEXCEL=0;
	public final static int FORMAT_OOXML=1;

	private DataFormatter useDataFormatter=null;
	private static final Log LOG = LogFactory.getLog(MSExcelLowFootprintParser.class.getName());
	private Map<Integer,List<SpreadSheetCellDAO[]>> spreadSheetCellDAOCache;
	private List<String> sheetNameList;
	private InputStream in;
	private String[] sheets=null;
	private HadoopOfficeReadConfiguration hocr;
	private int format;
	private int currentSheet;
	private int currentRow;
	
	public MSExcelLowFootprintParser(HadoopOfficeReadConfiguration hocr) {
		this(hocr, null);
	}

	/*
	*
	* Only process selected sheets (one after the other)
	*
	* @param hocr HadoopOffice configuration for reading files:
	* password Password of this document (null if no password)
	* metadataFilter filter on metadata. The name is the metadata attribute name and the property is a filter which contains a regular expression. Currently the following are supported for .xlsx documents: category,contentstatus, contenttype,created,creator,description,identifier,keywords,lastmodifiedbyuser,lastprinted,modified,revision,subject,title. Additionally all custom.* are defined as custom properties. Example custom.myproperty. Finally, matchAll can be set to true (all metadata needs to be matched), or false (at least one of the metadata item needs to match).
 Currently the following are supported for .xls documents: applicationname,author,charcount, comments, createdatetime,edittime,keywords,lastauthor,lastprinted,lastsavedatetime,pagecount,revnumber,security,subject,template,title,wordcount. Finally, matchAll can be set to true (all metadata needs to be matched), or false (at least one of the metadata item needs to match).
	* @param sheets selecrted sheets
	*
	*/
	public MSExcelLowFootprintParser(HadoopOfficeReadConfiguration hocr, String[] sheets) {
		this.sheets=sheets;
		this.hocr=hocr;
		if (hocr.getLocale()==null)  {
			useDataFormatter=new DataFormatter(); // use default locale
		} else {
			useDataFormatter=new DataFormatter(hocr.getLocale());
		}
		this.format=MSExcelLowFootprintParser.FORMAT_UNSUPPORTED; // will be detected when calling parse
		this.spreadSheetCellDAOCache=new HashMap<>();
		this.sheetNameList=new ArrayList<>();
		this.currentRow=0;
		this.currentSheet=0;
		// check not supported things and log
		if ((this.hocr.getReadLinkedWorkbooks()) || (this.hocr.getIgnoreMissingLinkedWorkbooks())) {
			LOG.warn("Linked workbooks not supported in low footprint parsing mode");
		}
		if ((this.hocr.getMetaDataFilter()!=null) && (this.hocr.getMetaDataFilter().size()>0))  {
			LOG.warn("Metadata filtering is not supported in low footprint parsing mode");
		}
	}
	
	/*
	*
	* Parses the given InputStream containing Excel data. The type of InputStream (e.g. FileInputStream, BufferedInputStream etc.) does not matter here, but it is recommended to use an appropriate
	* type to avoid performance issues. 
	*
	* @param in InputStream containing Excel data
	*
	* @throws org.zuinnote.hadoop.office.format.common.parser.FormatNotUnderstoodException in case there are issues reading from the Excel file, e.g. wrong password or unknown format
	*
	*/
	@Override
	public void parse(InputStream in) throws FormatNotUnderstoodException {
		this.currentRow=0;
		// detect workbook type (based on Workbookfactory code in Apache POI
		// If clearly doesn't do mark/reset, wrap up
		if(!in.markSupported()) {
					in = new PushbackInputStream(in, 8);
				}
		 try {
			byte[] header8 = IOUtils.peekFirst8Bytes(in);
		 
				if(NPOIFSFileSystem.hasPOIFSHeader(header8)) {
					LOG.info("Low footprint parsing of old Excel files (.xls)");
					 // use event model API for old Excel files
					this.format=MSExcelLowFootprintParser.FORMAT_OLDEXCEL;
					if (this.hocr.getPassword()!=null) {
						Biff8EncryptionKey.setCurrentUserPassword(this.hocr.getPassword());
					}
					NPOIFSFileSystem poifs = new NPOIFSFileSystem(in);
					InputStream din = poifs.createDocumentInputStream("Workbook");
					try {
					  HSSFRequest req = new HSSFRequest();
					  req.addListenerForAllRecords(new HSSFEventParser(this.sheetNameList,this.useDataFormatter,this.spreadSheetCellDAOCache,this.sheets));
					  HSSFEventFactory factory = new HSSFEventFactory();
					  factory.processEvents(req, din);
					}
					  finally {

						  Biff8EncryptionKey.setCurrentUserPassword(null);
						  din.close();
						  poifs.close();
					  }
				} else
				if(DocumentFactoryHelper.hasOOXMLHeader(in)) {

					LOG.info("Low footprint parsing of new Excel files (.xlsx)");
							// use event model API for new Excel files
					this.format=MSExcelLowFootprintParser.FORMAT_OOXML;
				} else {
					this.format=MSExcelLowFootprintParser.FORMAT_UNSUPPORTED;
					throw new FormatNotUnderstoodException("Could not detect Excel format in low footprint reading mode");
				}
		 } 
			
				catch (EmptyFileException | IOException e) {
					LOG.error(e);
					throw new FormatNotUnderstoodException("Could not detect format in Low footprint reading mode");
				}
		 finally {
		 	  if (this.in!=null) {
		 		  try {
					this.in.close();
				} catch (IOException e) {
					LOG.error(e);
					throw new FormatNotUnderstoodException("Error closing inputstream");
				}
		 	  }
		 }
	}

	@Override
	public long getCurrentRow() {
		return this.currentRow;
	}

	@Override
	public String getCurrentSheetName() {
		if (this.currentSheet>=this.sheetNameList.size()) {
			return this.sheetNameList.get(this.sheetNameList.size()-1);
		}
		return this.sheetNameList.get(this.currentSheet);
	}

	@Override
	public boolean addLinkedWorkbook(String name, InputStream inputStream, String password)
			throws FormatNotUnderstoodException {
		throw new FormatNotUnderstoodException("Workbooks are not supported in low footprint mode");
	}

	@Override
	public List<String> getLinkedWorkbooks() {
		return new ArrayList<>();
	}

	@Override
	public Object[] getNext() {
		SpreadSheetCellDAO[] result = null;
		if (this.currentRow<this.spreadSheetCellDAOCache.get(this.currentSheet).size()) {
			result=this.spreadSheetCellDAOCache.get(this.currentSheet).get(this.currentRow++);
		} 
		if (this.currentRow==this.spreadSheetCellDAOCache.get(this.currentSheet).size()) { // next sheet
			this.spreadSheetCellDAOCache.remove(this.currentSheet);
			this.currentSheet++;
			this.currentRow=0;
			
		}
		
		return result;
	}

	@Override
	public boolean getFiltered() {
		return true;
	}

	@Override
	public void close() throws IOException {
 	  if (this.in!=null) {
 		  this.in.close();
 	  }
		
	}
	
	/** Adapted from the Apache POI HowTos 
	 * https://poi.apache.org/spreadsheet/how-to.html
	 * 
	 * **/
	private static class XSSFEventParser extends DefaultHandler {
		private SharedStringsTable sst;
		private String lastContents;
		private boolean nextIsString;
		private List<SpreadSheetCellDAO[]> spreadSheetCellDAOCache;
		
		

		public void startElement(String uri, String localName, String name,
				Attributes attributes) throws SAXException {
			// c => cell
			if(name.equals("c")) {
				// Print the cell reference
				System.out.print(attributes.getValue("r") + " - ");
				// Figure out if the value is an index in the SST
				String cellType = attributes.getValue("t");
				if(cellType != null && cellType.equals("s")) {
					nextIsString = true;
				} else {
					nextIsString = false;
				}
			}
			// Clear contents cache
			lastContents = "";
		}
		
		public void endElement(String uri, String localName, String name)
				throws SAXException {
			// Process the last contents as required.
			// Do now, as characters() may be called more than once
			if(nextIsString) {
				int idx = Integer.parseInt(lastContents);
				lastContents = new XSSFRichTextString(sst.getEntryAt(idx)).toString();
				nextIsString = false;
			}

			// v => contents of a cell
			// Output after we've seen the string contents
			if(name.equals("v")) {
				System.out.println(lastContents);
			}
		}

		public void characters(char[] ch, int start, int length)
				throws SAXException {
			lastContents += new String(ch, start, length);
		}
	}
	
	
	/** Adapted the Apache POI HowTos 
	 * https://poi.apache.org/spreadsheet/how-to.html
	 * 
	 * **/
	private class HSSFEventParser implements HSSFListener {
		private Map<Integer,List<SpreadSheetCellDAO[]>> spreadSheetCellDAOCache; 
		private List<String> sheetList;
		private Map<Integer,Boolean> sheetMap;
		private Map<Integer,Long> sheetSizeMap;
		private List<Integer> extendedRecordFormatIndexList;
		private Map<Integer,String> formatRecordIndexMap;
		private DataFormatter useDataFormatter;
		private int currentSheet;
		private String[] sheets;
		private long currentCellNum;
		private int currentRowNum;
		private boolean readCachedFormulaResult;
		private int cachedRowNum;
		private short cachedColumnNum;
		private boolean currentSheetIgnore;
		private SSTRecord currentSSTrecord;

		public HSSFEventParser(List<String> sheetNameList,DataFormatter useDataFormatter, Map<Integer,List<SpreadSheetCellDAO[]>> spreadSheetCellDAOCache, String[] sheets) {
			this.spreadSheetCellDAOCache=spreadSheetCellDAOCache;
			this.sheets=sheets;
			this.currentCellNum=0L;
			this.currentRowNum=0;
			this.currentSheetIgnore=false;
			this.readCachedFormulaResult=false;
			this.cachedRowNum=0;
			this.cachedColumnNum=0;
			this.sheetList=new ArrayList<>();
			this.sheetMap=new HashMap<>();
			this.sheetSizeMap=new HashMap<>();
			this.currentSheet=0;
			this.extendedRecordFormatIndexList=new ArrayList<>();
			this.formatRecordIndexMap=new HashMap<>();
			this.sheetList=sheetNameList;
			this.useDataFormatter=useDataFormatter;
		}
		
		@Override
		public void processRecord(Record record) {
			switch (record.getSid()) // one should note that these do not arrive necessary in linear order. First all the sheets are processed. Then all the rows of the sheets
	        {
	            // the BOFRecord can represent either the beginning of a sheet or the workbook
	            case BOFRecord.sid:
	                BOFRecord bof = (BOFRecord) record;
	                if (bof.getType() == bof.TYPE_WORKBOOK)
	                {
	                    // ignored
	                } else if (bof.getType() == bof.TYPE_WORKSHEET)
	                {
	                    // ignored
	                }
	                break;
	            case BoundSheetRecord.sid:
	                BoundSheetRecord bsr = (BoundSheetRecord) record;
	                String currentSheet=bsr.getSheetname();
	                LOG.debug("Sheet found: "+currentSheet);
	                if (this.sheets==null) { // no sheets filter
	                	// ignore the filter
	                	this.sheetMap.put(this.sheetList.size(), true);
	                	this.sheetSizeMap.put(this.sheetList.size(), 0L);
                    	this.sheetList.add(currentSheet);
	                } else
	                if (currentSheet!=null) { // sheets filter
	                 	boolean found=false;
	                    for(int i=0;i<this.sheets.length;i++) {
	                    	if (currentSheet.equals(this.sheets[i])) {
	                    		found=true;
	                    		break;
	                    	}
	                    }
	                    this.sheetMap.put(this.sheetList.size(), found);
	                    this.sheetList.add(currentSheet);
	                } 
	                if (this.sheetMap.get(this.sheetList.size()-1)) { // create sheet
	                	 this.spreadSheetCellDAOCache.put(this.sheetList.size()-1, new ArrayList<SpreadSheetCellDAO[]>());
	                }
	                break;
	            case RowRecord.sid:
	            	  RowRecord rowRec = (RowRecord) record;
		              LOG.debug("Row found. Number of Cells: "+rowRec.getLastCol());
		              if ((this.currentSheet==0) && (rowRec.getRowNumber()==0)) { // first sheet
		            	  // special handling first sheet
		            	  this.currentSheet++;
		            	  this.currentCellNum=0;
		            	  this.currentRowNum=0;
		              } else
		              if ((this.currentSheet>0) && (rowRec.getRowNumber()==0)) { // new sheet
		            	  LOG.debug("Sheet number : "+this.currentSheet+" total number of cells "+this.currentCellNum);
		            	  this.sheetSizeMap.put(this.currentSheet-1, this.currentCellNum);
		            	  this.currentSheet++; // start processing next sheet
		            	  this.currentCellNum=0;
		            	  this.currentRowNum=0;
		              }
		              // create row if this sheet is supposed to be parsed
		              if (this.sheetMap.get(this.currentSheet-1)) {
		            	  this.spreadSheetCellDAOCache.get(this.currentSheet-1).add(new SpreadSheetCellDAO[rowRec.getLastCol()]);
		              }
		              this.currentRowNum++;
		              this.currentCellNum+=rowRec.getLastCol();
	                break;
	            case FormulaRecord.sid:
	            	LOG.debug("Formula Record found");
	            	// check if formula has a cached value
	            	FormulaRecord formRec=(FormulaRecord) record;
	            	/** check if this one should be parsed **/
	            	if (!this.sheetMap.get(this.currentSheet-1)) {// if not then do nothing
	            		break;
	            	}
	            	/** **/
	            	if (formRec.hasCachedResultString()) {
	            		this.readCachedFormulaResult=true;
	            		this.cachedColumnNum=formRec.getColumn();
	            		this.cachedRowNum=formRec.getRow();
	            	} else {
	            		// try to read the result
	            		if (formRec.getColumn()>=this.spreadSheetCellDAOCache.get(this.currentSheet-1).get(this.currentRowNum-1).length) {
	            			LOG.error("More cells in row than expected. Row number:"+(this.currentRowNum-1)+"Column number: "+formRec.getColumn()+"row length "+this.spreadSheetCellDAOCache.get(this.currentSheet-1).get(this.currentRowNum-1).length);
	                    	
	            		} else {

	            			int formatIndex= this.extendedRecordFormatIndexList.get(formRec.getXFIndex());
	            			String theNumber=this.useDataFormatter.formatRawCellContents(formRec.getValue(), formatIndex, this.formatRecordIndexMap.get(formatIndex));
	            			this.spreadSheetCellDAOCache.get(this.currentSheet-1).get(formRec.getRow())[formRec.getColumn()]=new SpreadSheetCellDAO(theNumber,"","",MSExcelUtil.getCellAddressA1Format(formRec.getRow(), formRec.getColumn()),this.sheetList.get(this.currentSheet-1));          			
	            		}
	            	}
	            	break;
	            case StringRecord.sid:  // read cached formula results, if available
	            	LOG.debug("String Record found");
	            	StringRecord strRec=(StringRecord) record;
	            	/** check if this one should be parsed **/
	               	if (!this.sheetMap.get(this.currentSheet-1)) {// if not then do nothing
	            		break;
	            	}
	            	/** **/
	              this.spreadSheetCellDAOCache.get(this.currentSheet-1).get(this.cachedRowNum)[this.cachedColumnNum]=new SpreadSheetCellDAO(strRec.getString(),"","",MSExcelUtil.getCellAddressA1Format(this.cachedRowNum,this.cachedColumnNum),this.sheetList.get(this.currentSheet-1));          			
	    	         this.readCachedFormulaResult=false;	
	            	
	            	break;
	            case NumberRecord.sid: // read number result
	            	LOG.debug("Number Record found");
	            	
	                NumberRecord numrec = (NumberRecord) record;
	           
	            
	                /** check if this one should be parsed **/
	               	if (!this.sheetMap.get(this.currentSheet-1)) {// if not then do nothing
	            		break;
	            	}
	            	/** **/
	            	// try to read the result
            		if (numrec.getColumn()>=this.spreadSheetCellDAOCache.get(this.currentSheet-1).get(this.currentRowNum-1).length) {
            			LOG.error("More cells in row than expected. Row number:"+(this.currentRowNum-1)+"Column number: "+numrec.getColumn()+"row length "+this.spreadSheetCellDAOCache.get(this.currentSheet-1).get(this.currentRowNum-1).length);
            		} else {
            			// convert the number in the right format (can be date etc.)
            			int formatIndex= this.extendedRecordFormatIndexList.get(numrec.getXFIndex());
            			String theNumber=this.useDataFormatter.formatRawCellContents(numrec.getValue(), formatIndex, this.formatRecordIndexMap.get(formatIndex));
            			   this.spreadSheetCellDAOCache.get(this.currentSheet-1).get(numrec.getRow())[numrec.getColumn()]=new SpreadSheetCellDAO(theNumber,"","",MSExcelUtil.getCellAddressA1Format(numrec.getRow(),numrec.getColumn()),this.sheetList.get(this.currentSheet-1));          		
            		}
	                break;
	                // SSTRecords store a array of unique strings used in Excel. (one per sheet?)
	            case SSTRecord.sid:
	            	LOG.debug("SST record found");
	          
	                this.currentSSTrecord=(SSTRecord) record;
	                break;
	            case LabelSSTRecord.sid: // get the string out of unique string value table 
	            	LOG.debug("Label found");
	                LabelSSTRecord lrec = (LabelSSTRecord) record;
	              	/** check if this one should be parsed **/
	               	if (!this.sheetMap.get(this.currentSheet-1)) {// if not then do nothing
	            		break;
	            	}
	            	/** **/
	            	if (lrec.getColumn()>=this.spreadSheetCellDAOCache.get(this.currentSheet-1).get(lrec.getRow()).length) {
	            		LOG.error("More cells in row than expected. Row number:"+(this.currentRowNum-1)+"Column number: "+lrec.getColumn()+"row length "+this.spreadSheetCellDAOCache.get(this.currentSheet-1).get(this.currentRowNum-1).length);
	                	
            		} else {
            			if ((lrec.getSSTIndex()<0) || (lrec.getSSTIndex()>=this.currentSSTrecord.getNumUniqueStrings())) {
            				LOG.error("Invalid SST record index. Cell ignored");
            			} else {
            				   this.spreadSheetCellDAOCache.get(this.currentSheet-1).get(lrec.getRow())[lrec.getColumn()]=new SpreadSheetCellDAO(this.currentSSTrecord.getString(lrec.getSSTIndex()).getString(),"","",MSExcelUtil.getCellAddressA1Format(lrec.getRow(),lrec.getColumn()),this.sheetList.get(this.currentSheet-1));          		
            	            	
            				
            			}
            		}
	                break;
	            case ExtendedFormatRecord.sid:
	            	ExtendedFormatRecord nfir = (ExtendedFormatRecord)record;
	            	this.extendedRecordFormatIndexList.add((int)nfir.getFormatIndex());
	               	
	            	
	            	break;
	            case FormatRecord.sid:
	            	FormatRecord frec = (FormatRecord)record;
	            	this.formatRecordIndexMap.put(frec.getIndexCode(),frec.getFormatString());
	            	
	            	break;
	          default:
	        	  //LOG.debug("Ignored record: "+record.getSid());
	        	  break;    
	        }
			
			
		}
		
	}
	
}