package com.thaiopensource.relaxng.output.rnc;

import com.thaiopensource.relaxng.edit.*;
import com.thaiopensource.relaxng.output.OutputDirectory;
import com.thaiopensource.relaxng.output.common.ErrorReporter;
import com.thaiopensource.relaxng.parse.SchemaBuilder;
import com.thaiopensource.xml.util.WellKnownNamespaces;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Vector;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;

/*
Annotations and comments (in progress)

Use \x{} escapes for characters not in repertoire of selected encoding

Avoid lines with excessive complexity

Make use of ##

Make long literals pretty

Take advantage of
  default namespace x = "..."
*/
class Output {
  private final Prettyprinter pp;
  private final String sourceUri;
  private final OutputDirectory od;
  private final ErrorReporter er;
  private final NamespaceManager.NamespaceBindings nsb;
  private final Map datatypeLibraryMap = new HashMap();
  private final NameClassVisitor nameClassOutput = new NameClassOutput(true);
  private final NameClassVisitor noParenNameClassOutput = new NameClassOutput(false);
  private final PatternVisitor noParenPatternOutput = new PatternOutput(false);
  private final PatternVisitor patternOutput = new PatternOutput(true);
  private final ComponentVisitor componentOutput = new ComponentOutput();
  private final AnnotationChildVisitor annotationChildOutput = new AnnotationChildOutput();
  private final AnnotationChildVisitor followingAnnotationChildOutput = new FollowingAnnotationChildOutput();
  private boolean isAttributeNameClass;

  static private final String indent = "  ";

  static private final String[] keywords = {
    "attribute", "default", "datatypes", "div", "element", "empty", "external",
    "grammar", "include", "inherit", "list", "mixed", "namespace", "notAllowed",
    "parent", "start", "string", "text", "token"
  };

  static private final Set keywordSet = new HashSet();

  static {
    for (int i = 0; i < keywords.length; i++)
      keywordSet.add(keywords[i]);
  }

  static void output(Pattern p, String sourceUri, OutputDirectory od, ErrorReporter er) throws IOException {
    try {
      new Output(sourceUri, od, er, NamespaceVisitor.createBindings(p)).topLevel(p);
    }
    catch (Prettyprinter.WrappedException e) {
      throw e.getIOException();
    }
  }

  private Output(String sourceUri, OutputDirectory od, ErrorReporter er,
                 NamespaceManager.NamespaceBindings nsb) throws IOException {
    this.sourceUri = sourceUri;
    this.od = od;
    this.er = er;
    this.pp = new StreamingPrettyprinter(od.getLineLength(), od.getLineSeparator(), od.open(sourceUri));
    this.nsb = nsb;
  }

  private void topLevel(Pattern p) {
    outputNamespaceDeclarations();
    outputDatatypeLibraryDeclarations(p);
    // XXX deal with annotations
    if (p instanceof GrammarPattern)
      innerBody(((GrammarPattern)p).getComponents());
    else
      p.accept(patternOutput);
    pp.hardNewline();
    pp.close();
  }

  private void outputNamespaceDeclarations() {
    List prefixes = new Vector();
    prefixes.addAll(nsb.getPrefixes());
    Collections.sort(prefixes);

    boolean needNewline = false;

    for (Iterator iter = prefixes.iterator(); iter.hasNext();) {
      String prefix = (String)iter.next();
      String ns = nsb.getNamespaceUri(prefix);
      if (prefix.length() == 0) {
        if (!ns.equals(SchemaBuilder.INHERIT_NS)) {
          pp.text("default namespace = ");
          literal(ns);
          pp.hardNewline();
          needNewline = true;
        }
      }
      else if (!prefix.equals("xml")) {
        pp.text("namespace ");
        pp.text(prefix);
        pp.text(" = ");
        if (ns.equals(SchemaBuilder.INHERIT_NS))
          pp.text("inherit");
        else
          literal(ns);
        pp.hardNewline();
        needNewline = true;
      }
    }

    if (needNewline)
      pp.hardNewline();
  }


  private void outputDatatypeLibraryDeclarations(Pattern p) {
    datatypeLibraryMap.put(WellKnownNamespaces.XML_SCHEMA_DATATYPES, "xsd");
    List datatypeLibraries = new Vector();
    datatypeLibraries.addAll(DatatypeLibraryVisitor.findDatatypeLibraries(p));
    if (datatypeLibraries.isEmpty())
      return;
    Collections.sort(datatypeLibraries);
    for (int i = 0, len = datatypeLibraries.size(); i < len; i++) {
      String prefix = "d";
      if (len > 1)
        prefix += Integer.toString(i + 1);
      String uri = (String)datatypeLibraries.get(i);
      datatypeLibraryMap.put(uri, prefix);
      pp.text("datatypes ");
      pp.text(prefix);
      pp.text(" = ");
      literal(uri);
      pp.hardNewline();
    }
    pp.hardNewline();
  }

  static class DatatypeLibraryVisitor extends NullVisitor {
    private Set datatypeLibraries = new HashSet();

    public void nullVisitValue(ValuePattern p) {
      noteDatatypeLibrary(p.getDatatypeLibrary());
      super.nullVisitValue(p);
    }

    public void nullVisitData(DataPattern p) {
      noteDatatypeLibrary(p.getDatatypeLibrary());
      super.nullVisitData(p);
    }

    private void noteDatatypeLibrary(String uri) {
      if (!uri.equals("") && !uri.equals(WellKnownNamespaces.XML_SCHEMA_DATATYPES))
        datatypeLibraries.add(uri);
    }

    static Set findDatatypeLibraries(Pattern p) {
      DatatypeLibraryVisitor dlv = new DatatypeLibraryVisitor();
      p.accept(dlv);
      return dlv.datatypeLibraries;
    }
  }

  static class NamespaceVisitor extends NullVisitor {
    private NamespaceManager nsm = new NamespaceManager();
    private boolean isAttribute;

    public void nullVisitInclude(IncludeComponent c) {
      super.nullVisitInclude(c);
      nsm.requireNamespace(c.getNs(), true);
    }

    public void nullVisitExternalRef(ExternalRefPattern p) {
      super.nullVisitExternalRef(p);
      nsm.requireNamespace(p.getNs(), true);
    }

    public void nullVisitElement(ElementPattern p) {
      isAttribute = false;
      super.nullVisitElement(p);
    }

    public void nullVisitAttribute(AttributePattern p) {
      isAttribute = true;
      super.nullVisitAttribute(p);
    }

    public void nullVisitName(NameNameClass nc) {
      super.nullVisitName(nc);
      if (!isAttribute || nc.getNamespaceUri().length() != 0)
        nsm.requireNamespace(nc.getNamespaceUri(), !isAttribute);
      if (nc.getPrefix() == null) {
        if (!isAttribute)
          nsm.preferBinding("", nc.getNamespaceUri());
      }
      else
        nsm.preferBinding(nc.getPrefix(), nc.getNamespaceUri());
    }

    public void nullVisitNsName(NsNameNameClass nc) {
      super.nullVisitNsName(nc);
      nsm.requireNamespace(nc.getNs(), false);
    }

    public void nullVisitValue(ValuePattern p) {
      super.nullVisitValue(p);
      for (Iterator iter = p.getPrefixMap().entrySet().iterator(); iter.hasNext();) {
        Map.Entry entry = (Map.Entry)iter.next();
        nsm.requireBinding((String)entry.getKey(), (String)entry.getValue());
      }
    }

    public void nullVisitElement(ElementAnnotation ea) {
      super.nullVisitElement(ea);
      noteAnnotationBinding(ea.getPrefix(), ea.getNamespaceUri());
    }

    public void nullVisitAttribute(AttributeAnnotation a) {
      super.nullVisitAttribute(a);
      noteAnnotationBinding(a.getPrefix(), a.getNamespaceUri());
    }

    private void noteAnnotationBinding(String prefix, String ns) {
      if (ns.length() != 0)
        nsm.requireNamespace(ns, false);
      if (prefix != null)
        nsm.preferBinding(prefix, ns);
    }

    static NamespaceManager.NamespaceBindings createBindings(Pattern p) {
      NamespaceVisitor nsv = new NamespaceVisitor();
      p.accept(nsv);
      return nsv.nsm.createBindings();
    }
  }

  class ComponentOutput implements ComponentVisitor {
    // XXX output annotations
    public Object visitDefine(DefineComponent c) {
      pp.startGroup();
      String name = c.getName();
      if (name == DefineComponent.START)
        pp.text("start");
      else
        identifier(name);
      Combine combine = c.getCombine();
      String op;
      if (combine == null)
        op = " =";
      else if (combine == Combine.CHOICE)
        op = " |=";
      else
        op = " &=";
      pp.text(op);
      pp.startNest(indent);
      pp.softNewline(" ");
      c.getBody().accept(noParenPatternOutput);
      pp.endNest();
      pp.endGroup();
      return null;
    }

    public Object visitDiv(DivComponent c) {
      pp.text("div");
      body(c);
      return null;
    }

    public Object visitInclude(IncludeComponent c) {
      pp.text("include ");
      literal(od.reference(sourceUri, c.getHref()));
      inherit(c.getNs());
      List components = c.getComponents();
      if (!components.isEmpty())
        body(components);
      return null;
    }
  }

  class PatternOutput implements PatternVisitor {
    private final boolean alwaysUseParens;

    PatternOutput(boolean alwaysUseParens) {
      this.alwaysUseParens = alwaysUseParens;
    }

    public Object visitGrammar(GrammarPattern p) {
      startAnnotations(p);
      pp.text("grammar");
      body(p);
      endAnnotations(p);
      return null;
    }

    public Object visitElement(ElementPattern p) {
      isAttributeNameClass = false;
      nameClassed(p, "element ");
      return null;
    }

    public Object visitAttribute(AttributePattern p) {
      isAttributeNameClass = true;
      nameClassed(p, "attribute ");
      return null;
    }

    private void nameClassed(NameClassedPattern p, String key) {
      startAnnotations(p);
      pp.text(key);
      pp.startNest(key);
      p.getNameClass().accept(noParenNameClassOutput);
      pp.endNest();
      pp.startGroup();
      pp.text(" {");
      pp.startNest(indent);
      pp.softNewline(" ");
      p.getChild().accept(noParenPatternOutput);
      pp.endNest();
      pp.softNewline(" ");
      pp.text("}");
      pp.endGroup();
      endAnnotations(p);
    }

    public Object visitOneOrMore(OneOrMorePattern p) {
      postfix(p, "+");
      return null;
    }

    public Object visitZeroOrMore(ZeroOrMorePattern p) {
      postfix(p, "*");
      return null;
    }

    public Object visitOptional(OptionalPattern p) {
      postfix(p, "?");
      return null;
    }

    private void postfix(UnaryPattern p, String op) {
      if (!startAnnotations(p)) {
        p.getChild().accept(patternOutput);
        pp.text(op);
      }
      else {
        pp.text("(");
        pp.startNest("(");
        p.getChild().accept(patternOutput);
        pp.endNest();
        pp.text(")");
        pp.text(op);
      }
      endAnnotations(p);
    }

    public Object visitRef(RefPattern p) {
      startAnnotations(p);
      identifier(p.getName());
      endAnnotations(p);
      return null;
    }

    public Object visitParentRef(ParentRefPattern p) {
      startAnnotations(p);
      pp.text("parent ");
      identifier(p.getName());
      endAnnotations(p);
      return null;
    }

    public Object visitExternalRef(ExternalRefPattern p) {
      startAnnotations(p);
      pp.text("external ");
      literal(od.reference(sourceUri, p.getHref()));
      inherit(p.getNs());
      endAnnotations(p);
      return null;
    }

    public Object visitText(TextPattern p) {
      startAnnotations(p);
      pp.text("text");
      endAnnotations(p);
      return null;
    }

    public Object visitEmpty(EmptyPattern p) {
      startAnnotations(p);
      pp.text("empty");
      endAnnotations(p);
      return null;
    }

    public Object visitNotAllowed(NotAllowedPattern p) {
      startAnnotations(p);
      pp.text("notAllowed");
      endAnnotations(p);
      return null;
    }

    public Object visitList(ListPattern p) {
      prefix(p, "list");
      return null;
    }

    public Object visitMixed(MixedPattern p) {
      prefix(p, "mixed");
      return null;
    }

    private void prefix(UnaryPattern p, String key) {
      startAnnotations(p);
      pp.text(key);
      pp.text(" {");
      pp.startNest(indent);
      pp.softNewline(" ");
      p.getChild().accept(noParenPatternOutput);
      pp.endNest();
      pp.softNewline(" ");
      pp.text("}");
      endAnnotations(p);
    }

    public Object visitChoice(ChoicePattern p) {
      composite(p, "| ", false);
      return null;
    }

    public Object visitInterleave(InterleavePattern p) {
      composite(p, "& ", false);
      return null;
    }

    public Object visitGroup(GroupPattern p) {
      composite(p, ",", true);
      return null;
    }

    void composite(CompositePattern p, String sep, boolean sepBeforeNewline) {
      boolean useParens = alwaysUseParens;
      if (startAnnotations(p))
        useParens = true;
      pp.startGroup();
      if (useParens) {
        pp.text("(");
        pp.startNest("(");
      }

      boolean first = true;
      for (Iterator iter = p.getChildren().iterator(); iter.hasNext();) {
        if (!first) {
          if (sepBeforeNewline)
            pp.text(sep);
          pp.softNewline(" ");
          if (!sepBeforeNewline) {
            pp.text(sep);
            pp.startNest(sep);
          }
        }
        ((Pattern)iter.next()).accept(patternOutput);
        if (first)
          first = false;
        else if (!sepBeforeNewline)
          pp.endNest();
      }
      if (useParens) {
        pp.endNest();
        pp.text(")");
      }
      pp.endGroup();
      endAnnotations(p);
    }

    public Object visitData(DataPattern p) {
      startAnnotations(p);
      String lib = p.getDatatypeLibrary();
      String qn;
      if (!lib.equals(""))
        qn = (String)datatypeLibraryMap.get(lib) + ":" + p.getType();
      else
        qn = p.getType();
      pp.text(qn);
      List params = p.getParams();
      if (params.size() > 0) {
        pp.startGroup();
        pp.text(" {");
        pp.startNest(indent);
        for (Iterator iter = params.iterator(); iter.hasNext();) {
          pp.softNewline(" ");
          // XXX output annotations for param
          Param param = (Param)iter.next();
          pp.text(param.getName());
          pp.text(" = ");
          literal(param.getValue());
        }
        pp.endNest();
        pp.softNewline(" ");
        pp.text("}");
        pp.endGroup();
      }
      Pattern e = p.getExcept();
      if (e != null) {
        if (params.size() == 0) {
          pp.text(" - ");
          pp.startNest(qn + " - ");
          e.accept(patternOutput);
          pp.endNest();
        }
        else {
          pp.startGroup();
          pp.softNewline(" ");
          pp.text("- ");
          pp.startNest("- ");
          // XXX think we need parentheses if e has following annotations
          e.accept(patternOutput);
          pp.endNest();
          pp.endGroup();
        }
      }
      endAnnotations(p);
      return null;
    }

    public Object visitValue(ValuePattern p) {
      startAnnotations(p);
      String lib = p.getDatatypeLibrary();
      if (lib.equals("")) {
        if (!p.getType().equals("token"))
          pp.text(p.getType() + " ");
      }
      else
        pp.text((String)datatypeLibraryMap.get(lib) + ":" + p.getType() + " ");
      literal(p.getValue());
      endAnnotations(p);
      return null;
    }

  }

  class NameClassOutput implements NameClassVisitor {
    private final boolean alwaysUseParens;

    NameClassOutput(boolean alwaysUseParens) {
      this.alwaysUseParens = alwaysUseParens;
    }

    public Object visitAnyName(AnyNameNameClass nc) {
      NameClass e = nc.getExcept();
      if (e == null) {
        startAnnotations(nc);
        pp.text("*");
      }
      else {
        boolean useParens = startAnnotations(nc) || alwaysUseParens;
        String s = useParens ?  "(* - " : "* - ";
        pp.text(s);
        pp.startNest(s);
        e.accept(nameClassOutput);
        if (useParens)
          pp.text(")");
        pp.endNest();
      }
      endAnnotations(nc);
      return null;
    }

    public Object visitNsName(NsNameNameClass nc) {
      NameClass e = nc.getExcept();
      String prefix = nsb.getNonEmptyPrefix(nc.getNs());
      if (e == null) {
        startAnnotations(nc);
        pp.text(prefix);
        pp.text(":*");
      }
      else {
        boolean useParens = startAnnotations(nc) || alwaysUseParens;
        String s = useParens ? "(" : "";
        s += prefix;
        s += ":* - ";
        pp.text(s);
        pp.startNest(s);
        e.accept(nameClassOutput);
        pp.endNest();
        if (useParens)
          pp.text(")");
      }
      endAnnotations(nc);
      return null;
    }

    public Object visitName(NameNameClass nc) {
      startAnnotations(nc);
      pp.text(qualifyName(nc.getNamespaceUri(), nc.getPrefix(), nc.getLocalName(), isAttributeNameClass));
      endAnnotations(nc);
      return null;
    }

    public Object visitChoice(ChoiceNameClass nc) {
      boolean useParens = alwaysUseParens;
      if (startAnnotations(nc))
        useParens = true;
      else if (nc.getChildren().size() == 1)
        useParens = false;
      if (useParens) {
        pp.text("(");
        pp.startNest("(");
      }
      pp.startGroup();
      boolean first = true;
      for (Iterator iter = nc.getChildren().iterator(); iter.hasNext();) {
        if (first)
          first = false;
        else {
          pp.softNewline(" ");
          pp.text("| ");
        }
        ((NameClass)iter.next()).accept(nameClassOutput);
      }
      pp.endGroup();
      if (useParens) {
        pp.endNest();
        pp.text(")");
      }
      endAnnotations(nc);
      return null;
    }
  }

  class AnnotationChildOutput implements AnnotationChildVisitor {
    public Object visitText(TextAnnotation ta) {
      literal(ta.getValue());
      return null;
    }

    public Object visitComment(Comment c) {
      pp.text("#");
      // XXX output the comment
      pp.hardNewline();
      return null;
    }

    public Object visitElement(ElementAnnotation elem) {
      pp.text(qualifyName(elem.getNamespaceUri(), elem.getPrefix(), elem.getLocalName(),
                          // unqualified annotation element names have "" namespace
                          true));
      pp.text(" ");
      annotationBody(elem.getAttributes(), elem.getChildren());
      return null;
    }
  }

  class FollowingAnnotationChildOutput extends AnnotationChildOutput {
    public Object visitElement(ElementAnnotation elem) {
      pp.text(">> ");
      pp.startNest(">> ");
      super.visitElement(elem);
      pp.endNest();
      return null;
    }
  }

  private static boolean hasAnnotations(Annotated annotated) {
    return (!annotated.getChildElementAnnotations().isEmpty()
            || !annotated.getAttributeAnnotations().isEmpty()
            || !annotated.getFollowingElementAnnotations().isEmpty());
  }

  private boolean startAnnotations(Annotated annotated) {
    if (!annotated.getLeadingComments().isEmpty()) {
      // XXX output the comments
      if (!hasAnnotations(annotated))
        return false;
    }
    else if (!hasAnnotations(annotated))
      return false;
    pp.startGroup();
    List before = (annotated.mayContainText()
                   ? annotated.getFollowingElementAnnotations()
                   : annotated.getChildElementAnnotations());
    if (!annotated.getAttributeAnnotations().isEmpty()
        || !before.isEmpty()) {
      annotationBody(annotated.getAttributeAnnotations(), before);
      pp.softNewline(" ");
    }
    return true;
  }

  private void endAnnotations(Annotated annotated) {
    if (!annotated.mayContainText()) {
      for (Iterator iter = annotated.getFollowingElementAnnotations().iterator(); iter.hasNext();) {
        pp.softNewline(" ");
        ((AnnotationChild)iter.next()).accept(followingAnnotationChildOutput);
      }
    }
    if (hasAnnotations(annotated))
      pp.endGroup();
  }

  private void annotationBody(List attributes, List children) {
    pp.startGroup();
    pp.text("[");
    pp.startNest(indent);
    for (Iterator iter = attributes.iterator(); iter.hasNext();) {
      AttributeAnnotation att = (AttributeAnnotation)iter.next();
      pp.softNewline(" ");
      pp.text(qualifyName(att.getNamespaceUri(), att.getPrefix(), att.getLocalName(), true));
      pp.text(" = ");
      literal(att.getValue());
    }
    for (Iterator iter = children.iterator(); iter.hasNext();) {
      pp.softNewline(" ");
      ((AnnotationChild)iter.next()).accept(annotationChildOutput);
    }
    pp.endNest();
    pp.softNewline(" ");
    pp.text("]");
    pp.endGroup();
  }

  private void body(Container container) {
    body(container.getComponents());
  }

  private void body(List components) {
    if (components.size() == 0)
      pp.text(" { }");
    else {
      pp.text(" {");
      pp.startNest(indent);
      pp.hardNewline();
      innerBody(components);
      pp.endNest();
      pp.hardNewline();
      pp.text("}");
    }
  }

  private void innerBody(List components) {
    boolean first = true;
    for (Iterator iter = components.iterator(); iter.hasNext();) {
      if (first)
        first = false;
      else
        pp.hardNewline();
      ((Component)iter.next()).accept(componentOutput);
    }
  }

  private void inherit(String ns) {
    if (ns.equals(nsb.getNamespaceUri("")))
      return;
    pp.text(" inherit = ");
    pp.text(nsb.getNonEmptyPrefix(ns));
  }

  private void identifier(String name) {
    if (keywordSet.contains(name))
      pp.text("\\");
    pp.text(name);
  }

  static final String[] delims = { "\"", "'", "\"\"\"", "'''" };

  private void literal(String str) {
    for (int i = 0, len = str.length();;) {
      // Find the delimiter that gives the longest segment
      String bestDelim = null;
      int bestEnd = -1;
      for (int j = 0; j < delims.length; j++) {
        int end = (str + delims[j]).indexOf(delims[j], i);
        if (end > bestEnd) {
          bestDelim = delims[j];
          bestEnd = end;
          if (end == len)
            break;
        }
      }
      if (i != 0)
        pp.text(" ~ ");
      pp.text(bestDelim);
      encode(str.substring(i, bestEnd));
      pp.text(bestDelim);
      i = bestEnd;
      if (i == len)
        break;
    }
  }

  private void encode(String str) {
    int start = 0;
    int len = str.length();
    for (int i = 0; i < len; i++) {
      switch (str.charAt(i)) {
      case '\\':
        if (!startsWithEscapeOpen(str, i))
          break;
        // fall through
      case '\r':
      case '\n':
        if (start < i)
          pp.text(str.substring(start, i));
        pp.text("\\x{");
        pp.text(Integer.toHexString(str.charAt(i)));
        pp.text("}");
        start = i + 1;
        break;
      }
    }
    if (start == 0)
      pp.text(str);
    else if (start != len)
      pp.text(str.substring(start, len));
  }

  static private boolean startsWithEscapeOpen(String str, int off) {
    if (!str.startsWith("\\x", off))
      return false;
    for (off += 2; str.startsWith("x", off); off++)
      ;
    return str.startsWith("{", off);
  }

  /**
   * null means no prefix
   */
  private String qualifyName(String ns, String prefix, String localName, boolean isAttribute) {
    prefix = choosePrefix(ns, prefix, isAttribute);
    if (prefix == null)
      return localName;
    StringBuffer buf = new StringBuffer(prefix);
    buf.append(':');
    buf.append(localName);
    return buf.toString();
  }

  /**
   * null means no prefix
   */
  private String choosePrefix(String ns, String prefix, boolean isAttribute) {
    if (prefix != null && ns.equals(nsb.getNamespaceUri(prefix)))
      return prefix;
    if (isAttribute) {
      if (ns.length() == 0)
        return null;
    }
    else {
      if (ns.equals(nsb.getNamespaceUri("")))
        return null;
    }
    return nsb.getNonEmptyPrefix(ns);
  }

}