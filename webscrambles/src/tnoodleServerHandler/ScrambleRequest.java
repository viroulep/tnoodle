package tnoodleServerHandler;

import static net.gnehzr.tnoodle.utils.Utils.GSON;
import static net.gnehzr.tnoodle.utils.Utils.toInt;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.SortedMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.gnehzr.tnoodle.scrambles.ScrambleCacher;
import net.gnehzr.tnoodle.scrambles.Scrambler;
import net.gnehzr.tnoodle.utils.BadClassDescriptionException;
import net.gnehzr.tnoodle.utils.LazyClassLoader;
import net.gnehzr.tnoodle.utils.Utils;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.Image;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.DefaultFontMapper;
import com.itextpdf.text.pdf.DefaultSplitCharacter;
import com.itextpdf.text.pdf.PdfChunk;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfImportedPage;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfSmartCopy;
import com.itextpdf.text.pdf.PdfTemplate;
import com.itextpdf.text.pdf.PdfWriter;

class ScrambleRequest {
	private static final int SCRAMBLES_PER_PAGE = 5;
	
	private static final int MAX_COUNT = 100;
	private static final int MAX_COPIES = 100;
	
	private static HashMap<String, ScrambleCacher> scrambleCachers = new HashMap<String, ScrambleCacher>();
	private static SortedMap<String, LazyClassLoader<Scrambler>> scramblers;
	static {
		try {
			scramblers = Scrambler.getScramblers();
		} catch (BadClassDescriptionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public ScrambleRequest(){}
	
	
	public String[] scrambles;
	public Scrambler scrambler;
	public int count;
	public int copies;
	public String title;
	public HashMap<String, Color> colorScheme;
	public ScrambleRequest(String title, String scrambleRequestUrl, String seed) throws InvalidScrambleRequestException, UnsupportedEncodingException {
		String[] puzzle_count_copies_scheme = scrambleRequestUrl.split("\\*");
		title = URLDecoder.decode(title, "utf-8");
		for(int i = 0; i < puzzle_count_copies_scheme.length; i++) {
			puzzle_count_copies_scheme[i] = URLDecoder.decode(puzzle_count_copies_scheme[i], "utf-8");
		}
		String countStr = "";
		String copiesStr = "";
		String scheme = "";
		String puzzle;
		switch(puzzle_count_copies_scheme.length) {
		case 4:
			scheme = puzzle_count_copies_scheme[3];
		case 3:
			copiesStr = puzzle_count_copies_scheme[2];
		case 2:
			countStr = puzzle_count_copies_scheme[1];
		case 1:
			puzzle = puzzle_count_copies_scheme[0];
			break;
		default:
			throw new InvalidScrambleRequestException("Invalid puzzle request " + scrambleRequestUrl);
		}
		
		LazyClassLoader<Scrambler> lazyScrambler = scramblers.get(puzzle);
		if(lazyScrambler == null) {
			throw new InvalidScrambleRequestException("Invalid scrambler: " + puzzle);
		}
		
		try {
			this.scrambler = lazyScrambler.cachedInstance();
		} catch (Exception e) {
			throw new InvalidScrambleRequestException(e);
		}
		
		ScrambleCacher scrambleCacher = scrambleCachers.get(puzzle);
		if(scrambleCacher == null) {
			scrambleCacher = new ScrambleCacher(scrambler);
			scrambleCachers.put(puzzle, scrambleCacher);
		}

		this.title = title;
		this.count = Math.min(toInt(countStr, 1), MAX_COUNT);
		this.copies = Math.min(toInt(copiesStr, 1), MAX_COPIES);
		if(seed != null) {
			this.scrambles = scrambler.generateSeededScrambles(seed, count);
		} else {
			this.scrambles = scrambleCacher.newScrambles(count);
		}
		
		this.colorScheme = scrambler.parseColorScheme(scheme);
	}
	
	public static ScrambleRequest[] parseScrambleRequests(LinkedHashMap<String, String> query, String seed) throws UnsupportedEncodingException, InvalidScrambleRequestException {
		ScrambleRequest[] scrambleRequests;
		if(query.size() == 0) {
			throw new InvalidScrambleRequestException("Must specify at least one scramble request");
		} else {
			scrambleRequests = new ScrambleRequest[query.size()];
			int i = 0;
			for(String title : query.keySet()) {
				// Note that we prefix the seed with the title of the round! This ensures that we get unique
				// scrambles in different rounds. Thanks to Ravi Fernando for noticing this at Stanford Fall 2011. 
				// (http://www.worldcubeassociation.org/results/c.php?i=StanfordFall2011).
				String uniqueSeed = null;
				if(seed != null) {
					uniqueSeed = title + seed;
				}
				scrambleRequests[i++] = new ScrambleRequest(title, query.get(title), uniqueSeed);
			}
		}
		return scrambleRequests;
	}
	

	private static final DefaultSplitCharacter SPLIT_ON_SPACES = new DefaultSplitCharacter() {
		@Override
		public boolean isSplitCharacter(int start, int current, int end, char[] cc, PdfChunk[] ck) {
			return getCurrentCharacter(current, cc, ck) == ' '; //only allow splitting on spaces
		}
	};


	private static PdfReader createPdf(String globalTitle, Date creationDate, ScrambleRequest scrambleRequest) throws DocumentException, IOException {
		ByteArrayOutputStream pdfOut = new ByteArrayOutputStream();
		Document doc = new Document(PageSize.LETTER, 0, 0, 75, 75);
		PdfWriter docWriter = PdfWriter.getInstance(doc, pdfOut);

		docWriter.setBoxSize("art", new Rectangle(36, 54, PageSize.LETTER.getWidth()-36, PageSize.LETTER.getHeight()-54));
		
		doc.addCreationDate();
		doc.addProducer();
//		doc.addAuthor(this.getClass().getName());
//		doc.addCreator(this.getClass().getName());
		if(globalTitle != null) {
			doc.addTitle(globalTitle);
		}
		
		doc.open();
		// Note that we ignore scrambleRequest.copies here.
		addScrambles(docWriter, doc, scrambleRequest);
		doc.close();
		
		// TODO - is there a better way to convert from a PdfWriter to a PdfReader?
		PdfReader pr = new PdfReader(pdfOut.toByteArray());
		
		pdfOut = new ByteArrayOutputStream();
		doc = new Document(PageSize.LETTER, 0, 0, 75, 75);
		docWriter = PdfWriter.getInstance(doc, pdfOut);
		doc.open();
		
		PdfContentByte cb = docWriter.getDirectContent();

		for(int pageN = 1; pageN <= pr.getNumberOfPages(); pageN++) {
			PdfImportedPage page = docWriter.getImportedPage(pr, pageN);
			
			doc.newPage();
			cb.addTemplate(page, 0, 0);

			Rectangle rect = pr.getBoxSize(pageN, "art");

			ColumnText.showTextAligned(cb,
					Element.ALIGN_LEFT, new Phrase(Utils.SDF.format(creationDate)),
					rect.getLeft(), rect.getTop(), 0);
			
			ColumnText.showTextAligned(cb,
					Element.ALIGN_CENTER, new Phrase(globalTitle),
					(rect.getLeft() + rect.getRight()) / 2, rect.getTop() + 5, 0);
			
			ColumnText.showTextAligned(cb,
					Element.ALIGN_CENTER, new Phrase(scrambleRequest.title),
					(rect.getLeft() + rect.getRight()) / 2, rect.getTop() - 10, 0);

			if(pr.getNumberOfPages() > 1) {
				ColumnText.showTextAligned(cb,
						Element.ALIGN_RIGHT, new Phrase(pageN + "/" + pr.getNumberOfPages()),
						rect.getRight(), rect.getTop(), 0);
			}
		}

		doc.close();

		// TODO - is there a better way to convert from a PdfWriter to a PdfReader?
		pr = new PdfReader(pdfOut.toByteArray());
		return pr;

//		The PdfStamper class doesn't seem to be working.
//		pdfOut = new ByteArrayOutputStream();
//		PdfStamper ps = new PdfStamper(pr, pdfOut);
//		
//		for(int pageN = 1; pageN <= pr.getNumberOfPages(); pageN++) {
//			PdfContentByte pb = ps.getUnderContent(pageN);
//			Rectangle rect = pr.getBoxSize(pageN, "art");
//			System.out.println(rect.getLeft());
//			System.out.println(rect.getWidth());
//	        ColumnText.showTextAligned(pb,
//	                Element.ALIGN_LEFT, new Phrase("Hello people!"), 36, 540, 0);
////			ColumnText.showTextAligned(pb,
////					Element.ALIGN_CENTER, new Phrase("HELLO WORLD"),
////					(rect.getLeft() + rect.getRight()) / 2, rect.getTop(), 0);
//		}
//		ps.close();
//		return ps.getReader();
	}
	
	private static void addScrambles(PdfWriter docWriter, Document doc, ScrambleRequest scrambleRequest) throws DocumentException {
		int width = 200;
		int height = (int) (PageSize.LETTER.getHeight()/SCRAMBLES_PER_PAGE);

		Dimension dim = scrambleRequest.scrambler.getPreferredSize(width, height);
		
		HashMap<String, Color> colorScheme = scrambleRequest.colorScheme;

		PdfPTable table = new PdfPTable(3);

		float maxWidth = 0;
		for(int i = 0; i < scrambleRequest.scrambles.length; i++) {
			String scramble = scrambleRequest.scrambles[i];
			Chunk ch = new Chunk((i+1)+".");
			maxWidth = Math.max(maxWidth, ch.getWidthPoint());
			PdfPCell nthscramble = new PdfPCell(new Paragraph(ch));
			nthscramble.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
			table.addCell(nthscramble);

			Chunk scrambleChunk = new Chunk(scramble);
			scrambleChunk.setSplitCharacter(SPLIT_ON_SPACES);
			try {
				BaseFont courier = BaseFont.createFont(BaseFont.COURIER, BaseFont.CP1252, BaseFont.EMBEDDED);
				scrambleChunk.setFont(new Font(courier, 12, Font.NORMAL));
			} catch(IOException e) {
				e.printStackTrace();
			} catch(DocumentException e) {
				e.printStackTrace();
			}
			PdfPCell scrambleCell = new PdfPCell(new Paragraph(scrambleChunk));
			scrambleCell.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
			table.addCell(scrambleCell);

			if(dim.width > 0 && dim.height > 0) {
				try {
					PdfContentByte cb = docWriter.getDirectContent();
					PdfTemplate tp = cb.createTemplate(dim.width, dim.height);
					Graphics2D g2 = tp.createGraphics(dim.width, dim.height, new DefaultFontMapper());

					scrambleRequest.scrambler.drawScramble(g2, dim, scramble, colorScheme);
					g2.dispose();
					PdfPCell imgCell = new PdfPCell(Image.getInstance(tp), true);
					imgCell.setBackgroundColor(BaseColor.GRAY);
					imgCell.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
					table.addCell(imgCell);
				} catch (Exception e) {
					table.addCell("Error drawing scramble: " + e.getMessage());
					e.printStackTrace();
				}
			} else {
				table.addCell("");
			}
		}
		maxWidth*=2; //TODO - I have no freaking clue why I need to do this.
		table.setTotalWidth(new float[] { maxWidth, doc.getPageSize().getWidth()-maxWidth-dim.width, dim.width });
		doc.add(table);
		doc.newPage();
	}
	
	public static ByteArrayOutputStream requestsToZip(String globalTitle, Date generationDate, ScrambleRequest[] scrambleRequests) throws IOException, DocumentException {
		ByteArrayOutputStream baosZip = new ByteArrayOutputStream();
		ZipOutputStream zipOut = new ZipOutputStream(baosZip);
		zipOut.setComment(globalTitle + " zip created on " + Utils.SDF.format(generationDate));
		for(ScrambleRequest scrambleRequest : scrambleRequests) {
			String fileName = scrambleRequest.title + ".pdf";
			ZipEntry entry = new ZipEntry(fileName);
			zipOut.putNextEntry(entry);

			PdfReader pdfReader = createPdf(globalTitle, generationDate, scrambleRequest);
			byte[] b = new byte[pdfReader.getFileLength()];
			pdfReader.getSafeFile().readFully(b);
			zipOut.write(b);

			zipOut.closeEntry();
		}
		zipOut.close();
		
		return baosZip;
	}

	public static ByteArrayOutputStream requestsToPdf(String globalTitle, Date generationDate, ScrambleRequest[] scrambleRequests) throws DocumentException, IOException {
		Document doc = new Document();
		ByteArrayOutputStream totalPdfOutput = new ByteArrayOutputStream();
		PdfSmartCopy totalPdfWriter = new PdfSmartCopy(doc, totalPdfOutput);
		doc.open();

		for(int i = 0; i < scrambleRequests.length; i++) {
			ScrambleRequest scrambleRequest = scrambleRequests[i];
			PdfReader pdfReader = createPdf(globalTitle, generationDate, scrambleRequest);
			for(int j = 0; j < scrambleRequest.copies; j++) {
				for(int pageN = 1; pageN <= pdfReader.getNumberOfPages(); pageN++) {
					PdfImportedPage page = totalPdfWriter.getImportedPage(pdfReader, pageN);
					totalPdfWriter.addPage(page);
				}
			}
		}
		doc.close();
		return totalPdfOutput;
	}
	
	public static void main(String[] args) throws UnsupportedEncodingException, InvalidScrambleRequestException {
		ScrambleRequest[] requests = new ScrambleRequest[] { new ScrambleRequest("title", "3x3x3", "seeding") };
		String json = GSON.toJson(requests);
		ScrambleRequest[] sr = GSON.fromJson(json, ScrambleRequest[].class);
		System.out.println(sr[0].scrambler);
	}
}
