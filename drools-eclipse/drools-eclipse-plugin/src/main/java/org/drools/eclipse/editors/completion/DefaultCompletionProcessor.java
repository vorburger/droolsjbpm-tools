package org.drools.eclipse.editors.completion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.drools.eclipse.DroolsEclipsePlugin;
import org.drools.eclipse.DroolsPluginImages;
import org.drools.eclipse.editors.AbstractRuleEditor;
import org.drools.eclipse.editors.DRLRuleEditor;
import org.drools.lang.descr.FactTemplateDescr;
import org.drools.lang.descr.GlobalDescr;
import org.drools.util.StringUtils;
import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.CompletionRequestor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.eval.IEvaluationContext;
import org.eclipse.jdt.internal.ui.text.java.AbstractJavaCompletionProposal;
import org.eclipse.jdt.internal.ui.text.java.JavaMethodCompletionProposal;
import org.eclipse.jdt.ui.text.java.CompletionProposalCollector;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;

/**
 * This is the basic completion processor that is used when the editor is outside of a rule block
 * partition.
 * The provides the content assistance for basic rule assembly stuff.
 *
 * This processor will also read behind the current editing position, to provide some context to
 * help provide the pop up list.
 *
 * @author Michael Neale, Kris Verlaenen
 */
public class DefaultCompletionProcessor extends AbstractCompletionProcessor {

    private static final String    NEW_RULE_TEMPLATE           = "rule \"new rule\"" + System.getProperty( "line.separator" ) + "\twhen" + System.getProperty( "line.separator" ) + "\t\t" + System.getProperty( "line.separator" ) + "\tthen"
                                                                 + System.getProperty( "line.separator" ) + "\t\t" + System.getProperty( "line.separator" ) + "end";
    private static final String    NEW_QUERY_TEMPLATE          = "query \"query name\"" + System.getProperty( "line.separator" ) + "\t#conditions" + System.getProperty( "line.separator" ) + "end";
    private static final String    NEW_FUNCTION_TEMPLATE       = "function void yourFunction(Type arg) {" + System.getProperty( "line.separator" ) + "\t/* code goes here*/" + System.getProperty( "line.separator" ) + "}";
    private static final String    NEW_TEMPLATE_TEMPLATE       = "template Name" + System.getProperty( "line.separator" ) + "\t" + System.getProperty( "line.separator" ) + "end";
    private static final Pattern   IMPORT_PATTERN              = Pattern.compile( ".*\n\\W*import\\W[^;\\s]*",
                                                                                  Pattern.DOTALL );
    // TODO: doesn't work for { inside functions
    private static final Pattern   FUNCTION_PATTERN            = Pattern.compile( ".*\n\\W*function\\s+(\\S+)\\s+(\\S+)\\s*\\(([^\\)]*)\\)\\s*\\{([^\\}]*)",
                                                                                  Pattern.DOTALL );
    protected static final Image   VARIABLE_ICON               = DroolsPluginImages.getImage( DroolsPluginImages.VARIABLE );
    protected static final Image   METHOD_ICON                 = DroolsPluginImages.getImage( DroolsPluginImages.METHOD );
    protected static final Image   CLASS_ICON                  = DroolsPluginImages.getImage( DroolsPluginImages.CLASS );

    protected static final Pattern START_OF_NEW_JAVA_STATEMENT = Pattern.compile( ".*[;{}]\\s*",
                                                                                  Pattern.DOTALL );

    public DefaultCompletionProcessor(AbstractRuleEditor editor) {
        super( editor );
    }

    protected List getCompletionProposals(ITextViewer viewer,
                                          int documentOffset) {
        try {
	        IDocument doc = viewer.getDocument();
	        String backText = readBackwards( documentOffset, doc );            
	
	        String prefix = CompletionUtil.stripLastWord(backText);
	        
	        List props = null;
	        Matcher matcher = IMPORT_PATTERN.matcher(backText); 
	        if (matcher.matches()) {
	        	String classNameStart = backText.substring(backText.lastIndexOf("import") + 7);
	        	props = getAllClassProposals(classNameStart, documentOffset, prefix);
	        } else {
	        	matcher = FUNCTION_PATTERN.matcher(backText);
	        	if (matcher.matches()) {
	        		// extract function parameters
		        	Map params = extractParams(matcher.group(3));
		        	// add global parameters
		        	List globals = getGlobals();
		    		if (globals != null) {
		    			for (Iterator iterator = globals.iterator(); iterator.hasNext(); ) {
		    				GlobalDescr global = (GlobalDescr) iterator.next();
		    				params.put(global.getIdentifier(), global.getType());
		    			}
		    		}
		        	String functionText = matcher.group(4);
	        		props = getJavaCompletionProposals(documentOffset, functionText, prefix, params);
	        		filterProposalsOnPrefix(prefix, props);
	        	} else {
	        		props = getPossibleProposals(viewer, documentOffset, backText, prefix);
	        	}
	        }
	        return props;
        } catch (Throwable t) {
        	DroolsEclipsePlugin.log(t);
        }
        return null;
    }

    private Map extractParams(String params) {
        Map result = new HashMap();
        String[] parameters = StringUtils.split( params,
                                                 "," );
        for ( int i = 0; i < parameters.length; i++ ) {
            String[] typeAndName = StringUtils.split( parameters[i] );
            if ( typeAndName.length == 2 ) {
                result.put( typeAndName[1],
                            typeAndName[0] );
            }
        }
        return result;
    }
    
	private List getAllClassProposals(final String classNameStart, final int documentOffset, final String prefix) {
		final List list = new ArrayList();
		IEditorInput input = getEditor().getEditorInput();
		if (input instanceof IFileEditorInput) {
			IProject project = ((IFileEditorInput) input).getFile().getProject();
			IJavaProject javaProject = JavaCore.create(project);
			
			CompletionRequestor requestor = new CompletionRequestor() {
				public void accept(org.eclipse.jdt.core.CompletionProposal proposal) {
					String className = new String(proposal.getCompletion());
					if (proposal.getKind() == org.eclipse.jdt.core.CompletionProposal.PACKAGE_REF) {
						RuleCompletionProposal prop = new RuleCompletionProposal(documentOffset - prefix.length(), classNameStart.length(), className, className + ".");
						prop.setImage(DroolsPluginImages.getImage(DroolsPluginImages.PACKAGE));
						list.add(prop);
					} else if (proposal.getKind() == org.eclipse.jdt.core.CompletionProposal.TYPE_REF) {
						RuleCompletionProposal prop = new RuleCompletionProposal(documentOffset - prefix.length(), classNameStart.length() - proposal.getReplaceStart(), className, className + ";"); 
						prop.setImage(DroolsPluginImages.getImage(DroolsPluginImages.CLASS));
						list.add(prop);
					}
					// ignore all other proposals
				}
			};

            try {
                javaProject.newEvaluationContext().codeComplete( classNameStart,
                                                                 classNameStart.length(),
                                                                 requestor );
            } catch ( Throwable t ) {
                DroolsEclipsePlugin.log( t );
            }
        }
        return list;
    }

    protected List getPossibleProposals(ITextViewer viewer,
                                        int documentOffset,
                                        String backText,
                                        final String prefix) {
        List list = new ArrayList();
        list.add(new RuleCompletionProposal(documentOffset - prefix.length(), prefix.length(), "rule", NEW_RULE_TEMPLATE, 6));
        list.add(new RuleCompletionProposal(documentOffset - prefix.length(), prefix.length(), "import", "import "));
        list.add(new RuleCompletionProposal(documentOffset - prefix.length(), prefix.length(), "expander", "expander "));
        list.add(new RuleCompletionProposal(documentOffset - prefix.length(), prefix.length(), "global", "global "));
        list.add(new RuleCompletionProposal(documentOffset - prefix.length(), prefix.length(), "package", "package "));
        list.add(new RuleCompletionProposal(documentOffset - prefix.length(), prefix.length(), "query", NEW_QUERY_TEMPLATE));
        list.add(new RuleCompletionProposal(documentOffset - prefix.length(), prefix.length(), "function", NEW_FUNCTION_TEMPLATE, 14));
        list.add(new RuleCompletionProposal(documentOffset - prefix.length(), prefix.length(), "template", NEW_TEMPLATE_TEMPLATE, 9));
        list.add(new RuleCompletionProposal(documentOffset - prefix.length(), prefix.length(), "dialect \"java\"", "dialect \"java\" "));
        list.add(new RuleCompletionProposal(documentOffset - prefix.length(), prefix.length(), "dialect \"mvel\"", "dialect \"mvel\" "));
        filterProposalsOnPrefix(prefix, list);
        return list;
    }

	protected List getJavaCompletionProposals(final int documentOffset, final String javaText, final String prefix, Map params) {
		final List list = new ArrayList();
		requestJavaCompletionProposals(javaText, prefix, documentOffset, params, list);
		return list;
	}

    /**
     * @return a list of "MVELified" RuleCompletionProposal. Thta list contains only unqiue proposal based on
     * the overrriden equals in {@link RuleCompletionProposal} to avoid the situation when several
     * accessors can exist for one property. for that case we want to keep only one proposal.
     */
    protected Collection getJavaMvelCompletionProposals(final String javaText,
			final String prefix, final int documentOffset, Map params) {
		final Collection set = new HashSet();
		CompletionRequestor requestor = new MvelCompletionRequestor(prefix,
				documentOffset, javaText, set);
		requestJavaMVELCompletionProposals(javaText, prefix, params, requestor);
		return set;
	}

	protected void requestJavaMVELCompletionProposals(final String javaText,
			final String prefix, Map params, CompletionRequestor requestor) {

    	// TODO different methods for MVEL and Java code completion
    	// now reusing Java completion proposals
    	// can this be used by MVEL as well?
		IEditorInput input = getEditor().getEditorInput();
		if (!(input instanceof IFileEditorInput)) {
			return;
		}
		IProject project = ((IFileEditorInput) input).getFile().getProject();
		IJavaProject javaProject = JavaCore.create(project);

		try {
			IEvaluationContext evalContext = javaProject.newEvaluationContext();
			List imports = getImports();
			if (imports != null && imports.size() > 0) {
				evalContext.setImports((String[]) imports
						.toArray(new String[imports.size()]));
			}
			StringBuffer javaTextWithParams = new StringBuffer();
			Iterator iterator = params.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry entry = (Map.Entry) iterator.next();
				// this does not seem to work, so adding variables manually
				// evalContext.newVariable((String) entry.getValue(), (String)
				// entry.getKey(), null);
				javaTextWithParams.append(entry.getValue() + " "
						+ entry.getKey() + ";\n");
			}
			javaTextWithParams.append("org.drools.spi.KnowledgeHelper drools;");
			javaTextWithParams.append(javaText);
			String text = javaTextWithParams.toString();
			// System.out.println( "" );
			// System.out.println( "MVEL: synthetic Java text:" + text );
			evalContext.codeComplete(text, text.length(), requestor);
		} catch (Throwable t) {
			DroolsEclipsePlugin.log(t);
		}
	}

    protected void requestJavaCompletionProposals(final String javaText,
                                                  final String prefix,
                                                  final int documentOffset,
                                                  Map params,
                                                  Collection results) {

		String javaTextWithoutPrefix = javaText.substring(0, javaText.length() - prefix.length());
		// boolean to filter default Object methods produced by code completion when in the beginning of a statement
		boolean filterObjectMethods = false;
		if ("".equals(javaTextWithoutPrefix.trim()) || START_OF_NEW_JAVA_STATEMENT.matcher(javaTextWithoutPrefix).matches()) {
			filterObjectMethods = true;
		}
        IEditorInput input = getEditor().getEditorInput();
        if ( !(input instanceof IFileEditorInput) ) {
            return;
        }
        IProject project = ((IFileEditorInput) input).getFile().getProject();
        IJavaProject javaProject = JavaCore.create( project );

		CompletionProposalCollector collector = new CompletionProposalCollector(javaProject);
		collector.acceptContext(new CompletionContext());
		
        try {
            IEvaluationContext evalContext = javaProject.newEvaluationContext();
            List imports = getImports();
            if ( imports != null && imports.size() > 0 ) {
                evalContext.setImports( (String[]) imports.toArray( new String[imports.size()] ) );
            }
            StringBuffer javaTextWithParams = new StringBuffer();
            Iterator iterator = params.entrySet().iterator();
            while ( iterator.hasNext() ) {
                Map.Entry entry = (Map.Entry) iterator.next();
                // this does not seem to work, so adding variables manually
                // evalContext.newVariable((String) entry.getValue(), (String) entry.getKey(), null);
                javaTextWithParams.append( entry.getValue() + " " + entry.getKey() + ";\n" );
            }
            javaTextWithParams.append( "org.drools.spi.KnowledgeHelper drools;" );
            javaTextWithParams.append( javaText );
            String text = javaTextWithParams.toString();
            evalContext.codeComplete( text,
                                      text.length(),
                                      collector );
			IJavaCompletionProposal[] proposals = collector.getJavaCompletionProposals();
			int replacementOffset = documentOffset - prefix.length();
			for (int i = 0; i < proposals.length; i++) {
				if (proposals[i] instanceof AbstractJavaCompletionProposal) {
					((AbstractJavaCompletionProposal) proposals[i]).setReplacementOffset(replacementOffset);
					if (!filterObjectMethods || !(proposals[i] instanceof JavaMethodCompletionProposal)) {
						results.add(proposals[i]);
					}
				}
			}
        } catch ( Throwable t ) {
            DroolsEclipsePlugin.log( t );
        }
    }

    protected String getPackage() {
        if ( getEditor() instanceof DRLRuleEditor ) {
            return ((DRLRuleEditor) getEditor()).getPackage();
        }
        return "";
    }

    protected List getImports() {
        if ( getEditor() instanceof DRLRuleEditor ) {
            return ((DRLRuleEditor) getEditor()).getImports();
        }
        return Collections.EMPTY_LIST;
    }

    protected List getFunctions() {
        if ( getEditor() instanceof DRLRuleEditor ) {
            return ((DRLRuleEditor) getEditor()).getFunctions();
        }
        return Collections.EMPTY_LIST;
    }

    protected Set getTemplates() {
        if ( getEditor() instanceof DRLRuleEditor ) {
            return ((DRLRuleEditor) getEditor()).getTemplates();
        }
        return Collections.EMPTY_SET;
    }

    protected FactTemplateDescr getTemplate(String name) {
        if ( getEditor() instanceof DRLRuleEditor ) {
            return ((DRLRuleEditor) getEditor()).getTemplate( name );
        }
        return null;
    }

    protected List getGlobals() {
        if ( getEditor() instanceof DRLRuleEditor ) {
            return ((DRLRuleEditor) getEditor()).getGlobals();
        }
        return Collections.EMPTY_LIST;
    }

    protected List getClassesInPackage() {
        if ( getEditor() instanceof DRLRuleEditor ) {
            return ((DRLRuleEditor) getEditor()).getClassesInPackage();
        }
        return Collections.EMPTY_LIST;
    }

    protected boolean isStartOfDialectExpression(String text) {
        return "".equals( text.trim() ) || START_OF_NEW_JAVA_STATEMENT.matcher( text ).matches();
    }
}
