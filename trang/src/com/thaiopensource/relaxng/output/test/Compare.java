package com.thaiopensource.relaxng.output.test;

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.SAXException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.helpers.DefaultHandler;

import java.util.List;
import java.util.Vector;
import java.util.Collections;
import java.util.Comparator;
import java.io.IOException;

import com.thaiopensource.util.UriOrFile;
import com.thaiopensource.relaxng.XMLReaderCreator;
import com.thaiopensource.relaxng.util.Jaxp11XMLReaderCreator;

public class Compare {
  static public boolean compare(String file1, String file2, XMLReaderCreator xrc) throws SAXException, IOException {
    return load(xrc, file1).equals(load(xrc, file2));
  }

  static private List load(XMLReaderCreator xrc, String file) throws SAXException, IOException {
    InputSource in = new InputSource(UriOrFile.fileToUri(file));
    Saver saver = new Saver();
    XMLReader xr = xrc.createXMLReader();

    try {
      xr.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
    }
    catch (SAXNotRecognizedException e) {
      throw new SAXException("support for namespaces-prefixes feature required");
    }
    catch (SAXNotSupportedException e) {
      throw new SAXException("support for namespaces-prefixes feature required");
    }
    xr.setContentHandler(saver);
    xr.parse(in);
    return saver.getEventList();
  }

  static abstract class Event {
    boolean merge(char[] chars, int start, int count) {
      return false;
    }
    boolean isWhitespace() {
      return false;
    }
  }

  static class StartElement extends Event {
    private final String qName;

    StartElement(String qName) {
      this.qName = qName;
    }

    public boolean equals(Object obj) {
      if (!(obj instanceof StartElement))
        return false;
      return qName.equals(((StartElement)obj).qName);
    }
  }

  static class Attribute extends Event {
    private final String qName;
    private final String value;

    Attribute(String qName, String value) {
      this.qName = qName;
      this.value = value;
    }

    public boolean equals(Object obj) {
      if (!(obj instanceof Attribute))
        return false;
      Attribute other = (Attribute)obj;
      return qName.equals(other.qName) && value.equals(other.value);
    }
  }

  static class EndElement extends Event {
    public boolean equals(Object obj) {
      return obj instanceof EndElement;
    }
  }

  static class Text extends Event {
    private String value;

    Text(String value) {
      this.value = value;
    }

    public boolean equals(Object obj) {
      if (!(obj instanceof Text))
        return false;
      return value.equals(((Text)obj).value);
    }

    boolean isWhitespace() {
      for (int i = 0, len = value.length(); i < len; i++) {
        switch (value.charAt(i)) {
        case '\r':
        case '\n':
        case '\t':
        case ' ':
          break;
        default:
          return false;
        }
      }
      return true;
    }

    boolean merge(char[] chars, int start, int count) {
      StringBuffer buf = new StringBuffer(value);
      buf.append(chars, start, count);
      value = buf.toString();
      return true;
    }
  }

  static class Saver extends DefaultHandler {
    private List eventList = new Vector();
    private List attributeList = new Vector();

    List getEventList() {
      return eventList;
    }

    void flushWhitespace() {
      int len = eventList.size();
      if (len == 0)
        return;
      if (((Event)eventList.get(len - 1)).isWhitespace())
        eventList.remove(len - 1);
    }

    public void startElement(String ns, String localName, String qName, Attributes attributes) {
      flushWhitespace();
      eventList.add(new StartElement(qName));
      for (int i = 0, len = attributes.getLength(); i < len; i++)
        attributeList.add(new Attribute(attributes.getQName(i), attributes.getValue(i)));
      Collections.sort(attributeList, new Comparator() {
        public int compare(Object o1, Object o2) {
          return ((Attribute)o1).qName.compareTo(((Attribute)o2).qName);
        }
      });
      eventList.addAll(attributeList);
      attributeList.clear();
    }

    public void endElement(String ns, String localName, String qName) {
      flushWhitespace();
      eventList.add(new EndElement());
    }

    public void characters(char[] chars, int start, int length) {
      int len = eventList.size();
      if (len == 0 || !((Event)eventList.get(len - 1)).merge(chars, start, length))
        eventList.add(new Text(new String(chars, start, length)));
    }

    public void ignorableWhitespace(char[] chars, int start, int length) {
      characters(chars, start, length);
    }

    public void endDocument() {
      flushWhitespace();
    }
  }

  static public void main(String[] args) throws SAXException, IOException {
    System.err.println(compare(args[0], args[1], new Jaxp11XMLReaderCreator()));
  }
}