// Copyright (c) 2012-2014 K Team. All Rights Reserved.
package org.kframework.parser.generator;

import org.kframework.kil.ASTNode;
import org.kframework.kil.Configuration;
import org.kframework.kil.Definition;
import org.kframework.kil.Location;
import org.kframework.kil.Module;
import org.kframework.kil.Sentence;
import org.kframework.kil.StringSentence;
import org.kframework.kil.Term;
import org.kframework.kil.loader.CollectStartSymbolPgmVisitor;
import org.kframework.kil.loader.Constants;
import org.kframework.kil.loader.Context;
import org.kframework.kil.loader.JavaClassesFactory;
import org.kframework.kil.visitors.ParseForestTransformer;
import org.kframework.parser.concrete2.Grammar;
import org.kframework.parser.concrete2.MakeConsList;
import org.kframework.parser.concrete2.Parser;
import org.kframework.parser.concrete2.TreeCleanerVisitor;
import org.kframework.utils.errorsystem.KException;
import org.kframework.utils.errorsystem.KException.ExceptionType;
import org.kframework.utils.errorsystem.KException.KExceptionGroup;
import org.kframework.utils.errorsystem.ParseFailedException;
import org.kframework.parser.concrete.disambiguate.AmbDuplicateFilter;
import org.kframework.parser.concrete.disambiguate.AmbFilter;
import org.kframework.parser.concrete.disambiguate.BestFitFilter;
import org.kframework.parser.concrete.disambiguate.CellEndLabelFilter;
import org.kframework.parser.concrete.disambiguate.CorrectCastPriorityFilter;
import org.kframework.parser.concrete.disambiguate.CorrectKSeqFilter;
import org.kframework.parser.concrete.disambiguate.FlattenListsFilter;
import org.kframework.parser.concrete.disambiguate.GetFitnessUnitKCheckVisitor;
import org.kframework.parser.concrete.disambiguate.InclusionFilter;
import org.kframework.parser.concrete.disambiguate.PreferAvoidFilter;
import org.kframework.parser.concrete.disambiguate.PreferDotsFilter;
import org.kframework.parser.concrete.disambiguate.PriorityFilter;
import org.kframework.parser.concrete.disambiguate.SentenceVariablesFilter;
import org.kframework.parser.concrete.disambiguate.VariableTypeInferenceFilter;
import org.kframework.utils.XmlLoader;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Formatter;

public class ParseConfigsFilter extends ParseForestTransformer {

    private final KExceptionManager kem;

    public ParseConfigsFilter(Context context, KExceptionManager kem) {
        super("Parse Configurations", context);
        this.kem = kem;
    }

    public ParseConfigsFilter(Context context, boolean checkInclusion, KExceptionManager kem) {
        this(context, kem);
        this.checkInclusion = checkInclusion;
    }

    boolean checkInclusion = true;

    @Override
    public ASTNode visit(Module m, Void _void) throws ParseFailedException {
        ASTNode rez = super.visit(m, _void);
        new CollectStartSymbolPgmVisitor(context).visitNode(rez);
        return rez;
    }

    public ASTNode visit(StringSentence ss, Void _void) throws ParseFailedException {
        if (ss.getType().equals(Constants.CONFIG)) {
            long startTime2 = System.currentTimeMillis();
            ASTNode config = null;
            if (!context.kompileOptions.experimental.javaParser) {
                String parsed = null;
                if (ss.containsAttribute("kore")) {
                    long startTime = System.currentTimeMillis();
                    parsed = org.kframework.parser.concrete.DefinitionLocalKParser.ParseKoreString(ss.getContent(), context.files.resolveKompiled("."));
                    if (context.globalOptions.verbose)
                        System.out.println("Parsing with Kore: " + ss.getSource() + ":" + ss.getLocation() + " - " + (System.currentTimeMillis() - startTime));
                } else {
                    try {
                        parsed = org.kframework.parser.concrete.DefinitionLocalKParser.ParseKConfigString(ss.getContent(), context.files.resolveKompiled("."));
                        // DISABLE EXCEPTION CHECKSTYLE
                    } catch (RuntimeException e) {
                        String msg = "SDF failed to parse a configuration by throwing: " + e.getCause().getLocalizedMessage();
                        throw new ParseFailedException(new KException(ExceptionType.ERROR, KExceptionGroup.CRITICAL, msg, ss.getSource(), ss.getLocation()));
                    }
                    // ENABLE EXCEPTION CHECKSTYLE
                }
                Document doc = XmlLoader.getXMLDoc(parsed);

                // replace the old xml node with the newly parsed sentence
                Node xmlTerm = doc.getFirstChild().getFirstChild().getNextSibling();
                XmlLoader.updateLocation(xmlTerm, XmlLoader.getLocNumber(ss.getContentLocation(), 0), XmlLoader.getLocNumber(ss.getContentLocation(), 1));
                XmlLoader.addSource(xmlTerm, ss.getSource());
                XmlLoader.reportErrors(doc, ss.getType());

                Sentence st = (Sentence) new JavaClassesFactory(context).getTerm((Element) xmlTerm);
                config = new Configuration(st);
                assert st.getLabel().equals(""); // labels should have been parsed in Outer Parsing
                st.setLabel(ss.getLabel());
                //assert st.getAttributes() == null || st.getAttributes().isEmpty(); // attributes should have been parsed in Outer Parsing
                st.setAttributes(ss.getAttributes());
                st.setLocation(ss.getLocation());
                st.setSource(ss.getSource());
            } else {
                // parse with the new parser for rules
                Grammar ruleGrammar = getCurrentModule().getRuleGrammar(kem);
                Parser parser = new Parser(ss.getContent());
                ASTNode out = parser.parse(ruleGrammar.get("MetaKList"), 0);
                try {
                    // TODO: update location information to match the actual position in the file
                    // only the unexpected character type of errors should be checked in this block
                    out = new TreeCleanerVisitor(context).visitNode(out);
                } catch (ParseFailedException te) {
                    Parser.ParseError perror = parser.getErrors();
                    String msg = ss.getContent().length() == perror.position ?
                            "Parse error: unexpected end of configuration." :
                            "Parse error: unexpected character '" + ss.getContent().charAt(perror.position) + "'.";
                    Location loc = new Location(perror.line, perror.column, perror.line, perror.column + 1);
                    throw new ParseFailedException(new KException(
                            ExceptionType.ERROR, KExceptionGroup.INNER_PARSER, msg, ss.getSource(), loc));
                }
                out = new MakeConsList(context).visitNode(out);
                Sentence st = new Sentence();
                st.setBody((Term) out);
                config = new Configuration(st);
            }

            // disambiguate configs
            config = new SentenceVariablesFilter(context).visitNode(config);
            config = new CellEndLabelFilter(context).visitNode(config);
            if (checkInclusion)
                config = new InclusionFilter(context, getCurrentDefinition(),
                        getCurrentModule()).visitNode(config);
            // config = new CellTypesFilter().visitNode(config); not the case on configs
            // config = new CorrectRewritePriorityFilter().visitNode(config);
            config = new CorrectKSeqFilter(context).visitNode(config);
            config = new CorrectCastPriorityFilter(context).visitNode(config);
            // config = new CheckBinaryPrecedenceFilter().visitNode(config);
            config = new PriorityFilter(context).visitNode(config);
            config = new PreferDotsFilter(context).visitNode(config);
            config = new VariableTypeInferenceFilter(context, kem).visitNode(config);
            // config = new AmbDuplicateFilter(context).visitNode(config);
            // config = new TypeSystemFilter(context).visitNode(config);
            // config = new BestFitFilter(new GetFitnessUnitTypeCheckVisitor(context), context).visitNode(config);
            // config = new TypeInferenceSupremumFilter(context).visitNode(config);
            config = new BestFitFilter(new GetFitnessUnitKCheckVisitor(context), context).visitNode(config);
            config = new PreferAvoidFilter(context).visitNode(config);
            config = new FlattenListsFilter(context).visitNode(config);
            config = new AmbDuplicateFilter(context).visitNode(config);
            // last resort disambiguation
            config = new AmbFilter(context, kem).visitNode(config);

            if (context.globalOptions.debug) {
                File file = context.files.resolveTemp("timing.log");
                if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                    throw KExceptionManager.criticalError("Could not create directory " + file.getParentFile());
                }
                try (Formatter f = new Formatter(new FileWriter(file, true))) {
                    f.format("Parsing config: Time: %6d Location: %s:%s%n", (System.currentTimeMillis() - startTime2), ss.getSource(), ss.getLocation());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return config;
        }
        return ss;
    }
}
