package com.konexio.bank.transactions.domain;

import com.konexio.bank.transactions.Transaction;
import com.konexio.bank.shared.money.Money;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/**
 * Renders a receipt as a one-page PDF.
 *
 * <p>Built from the standard PDF fonts only, so the document carries no embedded
 * font and stays a few kilobytes — a receipt is opened once on a phone with a
 * patchy connection, and the fastest thing to download is the thing that is not
 * there.
 *
 * <p>Those fonts are Latin-1, which is the one thing to watch: a name with
 * characters outside it would otherwise fail at render time, on the customer's
 * one attempt to save their receipt. {@link #latin1} replaces what cannot be
 * encoded rather than throwing, because a receipt with a substituted character
 * is still a receipt and an exception is not.
 */
@Component
class ReceiptPdf {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("d MMMM uuuu 'at' HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private static final float MARGIN = 56f;
    private static final float TITLE_SIZE = 20f;
    private static final float LABEL_SIZE = 10f;
    private static final float VALUE_SIZE = 12f;
    private static final float LINE_HEIGHT = 30f;

    byte[] render(TransactionReceipt receipt) {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN;
                y = writeHeading(content, receipt, y);
                for (Field field : fieldsOf(receipt)) {
                    y = writeField(content, field, y);
                }
                writeFooter(content, receipt);
            }

            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render receipt " + receipt.reference(), e);
        }
    }

    private float writeHeading(PDPageContentStream content, TransactionReceipt receipt, float y)
            throws IOException {
        write(content, bold(), TITLE_SIZE, MARGIN, y, "Konexio Bank");
        write(content, regular(), VALUE_SIZE, MARGIN, y - 22f, "Transaction receipt");

        // The amount is what the customer is looking for, so it is the second
        // thing on the page and the largest after the bank's name.
        String sign = receipt.direction().name().equals("IN") ? "+" : "-";
        write(content, bold(), TITLE_SIZE, MARGIN, y - 62f, sign + " " + money(receipt.amount()));
        return y - 110f;
    }

    private float writeField(PDPageContentStream content, Field field, float y) throws IOException {
        write(content, regular(), LABEL_SIZE, MARGIN, y, field.label().toUpperCase());
        write(content, bold(), VALUE_SIZE, MARGIN, y - 14f, field.value());
        return y - LINE_HEIGHT - 8f;
    }

    private void writeFooter(PDPageContentStream content, TransactionReceipt receipt) throws IOException {
        write(content, regular(), LABEL_SIZE, MARGIN, MARGIN + 14f,
                "Generated " + TIMESTAMP.format(java.time.Instant.now()));
        write(content, regular(), LABEL_SIZE, MARGIN, MARGIN,
                "Quote reference " + receipt.reference() + " in any query about this transaction.");
    }

    /** Only what is actually known: a blank line on a receipt invites the question "why is that empty?". */
    private static List<Field> fieldsOf(TransactionReceipt receipt) {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Reference", receipt.reference()));
        fields.add(new Field("Date", TIMESTAMP.format(receipt.postedAt())));
        fields.add(new Field("Type", readable(receipt.type().name())));
        if (receipt.channel() != null) {
            fields.add(new Field("Channel", readable(receipt.channel())));
        }
        fields.add(new Field("Description", receipt.description()));
        fields.add(new Field("Amount", money(receipt.amount())));
        if (!receipt.fee().isZero()) {
            fields.add(new Field("Fee", money(receipt.fee())));
            fields.add(new Field("Total", money(receipt.total())));
        }
        fields.add(new Field("Account", partyLine(receipt.account())));
        fields.add(new Field(
                receipt.direction().name().equals("IN") ? "From" : "To", partyLine(receipt.counterparty())));
        fields.add(new Field("Balance after", money(receipt.balanceAfter())));
        return fields;
    }

    private static String partyLine(TransactionParty party) {
        StringBuilder line = new StringBuilder(party.name());
        if (party.accountNumber() != null) {
            line.append("  ").append(party.accountNumber());
        }
        if (party.detail() != null) {
            line.append("  ").append(party.detail());
        }
        return line.toString();
    }

    private static String money(Money amount) {
        return amount.currency() + " " + amount.amount().toPlainString();
    }

    /** {@code LOAN_DISBURSEMENT} reads as "Loan disbursement" on a customer's receipt. */
    private static String readable(String constant) {
        String spaced = constant.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static void write(
            PDPageContentStream content, PDType1Font font, float size, float x, float y, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(latin1(text));
        content.endText();
    }

    /**
     * The standard fonts encode Latin-1 only. The masked account numbers use
     * {@code •}, which is not in it, so that at least has to be translated — and
     * anything else unencodable becomes a question mark rather than an exception
     * thrown in the customer's face.
     */
    private static String latin1(String text) {
        StringBuilder encodable = new StringBuilder(text.length());
        for (char character : text.toCharArray()) {
            if (character == '•') {
                encodable.append('*');
            } else if (character < 0x100) {
                encodable.append(character);
            } else {
                encodable.append('?');
            }
        }
        return encodable.toString();
    }

    private static PDType1Font regular() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private static PDType1Font bold() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    }

    private record Field(String label, String value) {}
}
