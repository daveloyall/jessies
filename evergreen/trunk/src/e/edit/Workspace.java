package e.edit;

import e.gui.*;
import e.ptextarea.*;
import e.util.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

public class Workspace extends JPanel {
    private EColumn leftColumn = new EColumn();
    
    private ArrayList<EErrorsWindow> errorsWindows = new ArrayList<EErrorsWindow>();

    private String workspaceName;
    private String rootDirectory;
    private String canonicalRootDirectory;
    private String buildTarget;
    
    private OpenQuicklyDialog openQuicklyDialog;
    private FindInFilesDialog findInFilesDialog;
    
    private EFileDialog openDialog;
    
    private WorkspaceFileList fileList;
    
    private ETextWindow rememberedTextWindow;
    
    private List<Evergreen.InitialFile> initialFiles = Collections.emptyList();
    
    public Workspace(String workspaceName, final String rootDirectory) {
        super(new BorderLayout());
        
        initFileList();
        
        setWorkspaceName(workspaceName);
        setRootDirectory(rootDirectory);
        setBuildTarget("");
        
        add(leftColumn, BorderLayout.CENTER);
        leftColumn.addContainerListener(new ContainerListener() {
            public void componentAdded(ContainerEvent e) {
                componentCountChanged();
            }
            public void componentRemoved(ContainerEvent e) {
                componentCountChanged();
            }
            private void componentCountChanged() {
                updateTabForWorkspace();
            }
        });
    }
    
    private void initFileList() {
        this.fileList = new WorkspaceFileList(this);
        fileList.addFileListListener(new WorkspaceFileList.Listener() {
            public void fileListStateChanged(boolean newState) {
                updateTabForWorkspace();
            }
        });
    }
    
    public WorkspaceFileList getFileList() {
        return fileList;
    }
    
    public void dispose() {
        fileList.dispose();
    }
    
    public String getWorkspaceName() {
        return workspaceName;
    }
    
    public void setWorkspaceName(String newWorkspaceName) {
        this.workspaceName = newWorkspaceName;
        updateTabForWorkspace();
    }
    
    /**
     * Updates the title of the tab in the JTabbedPane that corresponds to the Workspace that this
     * EColumn represents (if you can follow that). Invoked when the column has a component added
     * or removed.
     */
    private void updateTabForWorkspace() {
        JTabbedPane tabbedPane = (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, this);
        if (tabbedPane != null) {
            final File rootDirectory = FileUtilities.fileFromString(getRootDirectory());
            final boolean rootDirectoryValid = rootDirectory.exists() && rootDirectory.isDirectory();
            final int openFileCount = leftColumn.getTextWindows().size() + getInitialFileCount();
            
            String tabTitle = getWorkspaceName();
            if (openFileCount > 0) {
                tabTitle += " (" + openFileCount + ")";
            }
            
            final int tabIndex = tabbedPane.indexOfComponent(this);
            tabbedPane.setForegroundAt(tabIndex, rootDirectoryValid ? Color.BLACK : Color.RED);
            tabbedPane.setTitleAt(tabIndex, tabTitle);
        }
    }
    
    /**
     * Returns the normal, friendly form rather than the OS-canonical one.
     * See also getCanonicalRootDirectory.
     * Ends with File.separator.
     */
    public String getRootDirectory() {
        return rootDirectory;
    }
    
    public void setRootDirectory(String newRootDirectory) {
        this.rootDirectory = FileUtilities.getUserFriendlyName(newRootDirectory);
        try {
            this.canonicalRootDirectory = FileUtilities.fileFromString(rootDirectory).getCanonicalPath() + File.separator;
        } catch (IOException ex) {
            Log.warn("Failed to cache canonical root directory for workspace \"" + workspaceName + "\" with new root \"" + rootDirectory + "\"", ex);
        }
        fileList.rootDidChange();
        updateTabForWorkspace();
    }
    
    /**
     * Returns an absolute path in user-friendly form.
     * This avoids any File.separator confusion.
     */
    public String prependRootDirectory(String workspaceRelativeFilename) {
        return rootDirectory + workspaceRelativeFilename;
    }
    
    /**
     * Returns the OS-canonical form rather than the normal, friendly one.
     * See also getRootDirectory.
     */
    public String getCanonicalRootDirectory() {
        // We cache this because it's called when deciding on a workspace for a newly-opened file.
        // We don't want the UI to lock up because an unrelated workspace's NFS mount is temporarily unresponsive.
        return canonicalRootDirectory;
    }
    
    /** Tests whether this workspace is empty. A workspace is still considered empty if all it contains is an errors window. */
    public boolean isEmpty() {
        return leftColumn.getTextWindows().isEmpty();
    }
    
    public Collection<ETextWindow> getTextWindows() {
        return leftColumn.getTextWindows();
    }
    
    /** Returns an array of this workspace's dirty text windows. */
    public Collection<ETextWindow> getDirtyTextWindows() {
        ArrayList<ETextWindow> dirtyTextWindows = new ArrayList<ETextWindow>();
        for (ETextWindow textWindow : leftColumn.getTextWindows()) {
            if (textWindow.isDirty()) {
                dirtyTextWindows.add(textWindow);
            }
        }
        return dirtyTextWindows;
    }
    
    /**
     * Returns the EWindow corresponding to the given file. If the file's open, shows the given address.
     * If the caller were to supply a different filename for the same file, then this wouldn't notice that the file was already open.
     */
    public EWindow findIfAlreadyOpen(String filename, String address) {
        // Check we don't already have this open as a file or directory.
        final EWindow window = leftColumn.findWindowByFilename(filename);
        if (window != null) {
            leftColumn.setSelectedWindow(window);
            window.ensureSufficientlyVisible();
            ETextWindow textWindow = (ETextWindow) window;
            textWindow.jumpToAddress(address);
            EventQueue.invokeLater(new Runnable() {
                public void run() {
                    window.requestFocus();
                }
            });
            return window;
        }
        return null;
    }
    
    public EWindow addViewerForFile(final String filename, final String address, final int y) {
        Evergreen.getInstance().showStatus("Opening " + filename + "...");
        EWindow window = null;
        try {
            ETextWindow newWindow = new ETextWindow(filename);
            window = addViewer(newWindow, address, y);
            if (filename.startsWith(getRootDirectory())) {
                int prefixCharsToSkip = getRootDirectory().length();
                String pathWithinWorkspace = filename.substring(prefixCharsToSkip);
                fileList.ensureInFileList(pathWithinWorkspace);
            }
        } catch (Exception ex) {
            Log.warn("Exception while opening file", ex);
        }
        Evergreen.getInstance().showStatus("Opened " + filename);
        return window;
    }
    
    private EWindow addViewer(final EWindow viewer, final String address, final int y) {
        leftColumn.addComponent(viewer, y);
        if (address != null) {
            final ETextWindow textWindow = (ETextWindow) viewer;
            EventQueue.invokeLater(new Runnable() {
                public void run() {
                    textWindow.jumpToAddress(address);
                }
            });
        }
        
        if (Evergreen.getInstance().isInitialized()) {
            EventQueue.invokeLater(new Runnable() {
                public void run() {
                    viewer.requestFocus();
                }
            });
        }
        return viewer;
    }
    
    /**
     * Checks whether this workspace has unsaved files before performing some action.
     * Offers the user the choice of continuing without saving, continuing after saving, or
     * canceling.
     * Returns true if you should continue with your action, false if the user hit 'Cancel'.
     */
    public boolean prepareForAction(String activity, String unsavedDataPrompt) {
        if (!getDirtyTextWindows().isEmpty()) {
            String choice = Evergreen.getInstance().askQuestion(activity, unsavedDataPrompt, "Save All", "Don't Save");
            if (choice.equals("Cancel")) {
                return false;
            }
            boolean saveAll = choice.equals("Save All");
            if (saveAll == false) {
                return true;
            }
            boolean okay = saveAll();
            if (okay == false) {
                // Pretend the user canceled, because we shouldn't pretend that everything went okay.
                return false;
            }
        }
        return true;
    }
    
    /**
     * Attempts to save all the dirty files on this workspace.
     * Returns true if all saves were successful, false otherwise.
     */
    public boolean saveAll() {
        for (ETextWindow dirtyWindow : getDirtyTextWindows()) {
            boolean savedOkay = dirtyWindow.save();
            if (savedOkay == false) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Shows the "Find File" dialog with the given String as the contents of
     * the text field. Use the empty string to retain the current contents.
     * There's no way to empty the text field's contents, but that sounds like
     * a bad idea anyway. Either you've got a better suggestion than what the
     * user last typed, or you should leave things as they are.
     */
    public synchronized void showFindInFilesDialog(String pattern, String filenamePattern) {
        if (findInFilesDialog == null) {
            findInFilesDialog = new FindInFilesDialog(this);
        }
        if (pattern.length() != 0) {
            findInFilesDialog.setPattern(pattern);
        }
        if (filenamePattern.length() != 0) {
            findInFilesDialog.setFilenamePattern(filenamePattern);
        }
        findInFilesDialog.showDialog();
    }
    
    /**
     * Shows the "Open Quickly" dialog with the given String as the contents
     * of the text field. Use the empty string to retain the current contents.
     * There's no way to empty the text field's contents, but that sounds like
     * a bad idea anyway. Either you've got a better suggestion than what the
     * user last typed, or you should leave things as they are.
     */
    public void showOpenQuicklyDialog(String filenamePattern) {
        if (openQuicklyDialog == null) {
            openQuicklyDialog = new OpenQuicklyDialog(this);
        }
        if (filenamePattern.length() != 0) {
            openQuicklyDialog.setFilenamePattern(filenamePattern);
        }
        openQuicklyDialog.showDialog();
    }
    
    public void showOpenDialog() {
        if (openDialog == null) {
            openDialog = EFileDialog.makeOpenDialog(Evergreen.getInstance().getFrame(), getRootDirectory());
        }
        openDialog.show();
        String name = openDialog.getFile();
        if (name != null) {
            Evergreen.getInstance().openFile(name);
        }
    }

    public void takeWindow(EWindow window) {
        window.removeFromColumn();
        leftColumn.addComponent(window, -1);
    }
    
    public void moveFilesToBestWorkspaces() {
        for (ETextWindow textWindow : leftColumn.getTextWindows()) {
            Workspace bestWorkspace = Evergreen.getInstance().getBestWorkspaceForFilename(textWindow.getFilename(), this);
            if (bestWorkspace != this) {
                bestWorkspace.takeWindow(textWindow);
            }
        }
    }
    
    public void rememberFocusedTextWindow(ETextWindow textWindow) {
        rememberedTextWindow = textWindow;
    }
    
    public void restoreFocusToRememberedTextWindow() {
        if (rememberedTextWindow != null) {
            EventQueue.invokeLater(new Runnable() {
                public void run() {
                    rememberedTextWindow.requestFocus();
                }
            });
        }
    }
    
    public String getBuildTarget() {
        return buildTarget;
    }
    
    public void setBuildTarget(String newBuildTarget) {
        this.buildTarget = (newBuildTarget != null) ? newBuildTarget : "";
    }
    
    public void serializeAsXml(org.w3c.dom.Document document, org.w3c.dom.Element workspaces) {
        org.w3c.dom.Element workspace = document.createElement("workspace");
        workspaces.appendChild(workspace);
        workspace.setAttribute("name", getWorkspaceName());
        workspace.setAttribute("root", getRootDirectory());
        workspace.setAttribute("buildTarget", getBuildTarget());
        if (Evergreen.getInstance().getCurrentWorkspace() == this) {
            workspace.setAttribute("selected", "true");
        }
        // Remember any files that were actually shown.
        for (ETextWindow textWindow : leftColumn.getTextWindows()) {
            org.w3c.dom.Element file = document.createElement("file");
            workspace.appendChild(file);
            file.setAttribute("name", textWindow.getFilename() + textWindow.getAddress());
            file.setAttribute("y", Integer.toString(textWindow.getY()));
            if (textWindow == rememberedTextWindow) {
                file.setAttribute("lastFocused", "true");
            }
        }
        // Remember any files that would have been shown, had this workspace ever been shown.
        for (Evergreen.InitialFile initialFile : initialFiles) {
            org.w3c.dom.Element file = document.createElement("file");
            workspace.appendChild(file);
            file.setAttribute("name", initialFile.filename);
            file.setAttribute("y", Integer.toString(initialFile.y));
            if (initialFile.lastFocused) {
                file.setAttribute("lastFocused", "true");
            }
        }
    }
    
    public void setInitialFiles(List<Evergreen.InitialFile> newInitialFiles) {
        this.initialFiles = newInitialFiles;
        updateTabForWorkspace();
    }
    
    public int getInitialFileCount() {
        synchronized (initialFiles) {
            return initialFiles.size();
        }
    }
    
    /** Opens all the files listed in the file we remembered them to last time we quit. */
    public void openRememberedFiles() {
        synchronized (initialFiles) {
            for (Evergreen.InitialFile file : initialFiles) {
                ETextWindow fileWindow = (ETextWindow) Evergreen.getInstance().openFile(file);
                if (file.lastFocused) {
                    rememberedTextWindow = fileWindow;
                }
            }
            initialFiles.clear();
            updateTabForWorkspace();
            restoreFocusToRememberedTextWindow();
        }
    }
    
    public void preferencesChanged() {
        for (ETextWindow textWindow : getTextWindows()) {
            textWindow.preferencesChanged();
        }
        for (EErrorsWindow errorsWindow : errorsWindows) {
            errorsWindow.initFont();
        }
    }
    
    public EErrorsWindow createErrorsWindow(String windowTitle) {
        synchronized (errorsWindows) {
            final EErrorsWindow errorsWindow = new EErrorsWindow(this, windowTitle);
            errorsWindow.addWindowListener(new WindowAdapter() {
                public void windowClosed(WindowEvent e) {
                    destroyErrorsWindow(errorsWindow);
                }
            });
            errorsWindows.add(errorsWindow);
            return errorsWindow;
        }
    }
    
    private void destroyErrorsWindow(EErrorsWindow errorsWindow) {
        synchronized (errorsWindows) {
            errorsWindows.remove(errorsWindow);
        }
    }
    
    public void clearTopErrorsWindow() {
        synchronized (errorsWindows) {
            if (errorsWindows.size() > 0) {
                errorsWindows.get(errorsWindows.size() - 1).clearErrors();
            }
        }
    }
    
    public ShellCommand makeShellCommand(ETextWindow textWindow, String directory, String command, ToolInputDisposition inputDisposition, ToolOutputDisposition outputDisposition) {
        final Map<String, String> environment = new TreeMap<String, String>();
        environment.put("EVERGREEN_CURRENT_DIRECTORY", FileUtilities.translateFilenameForShellUse(directory));
        environment.put("EVERGREEN_LAUNCHER", Evergreen.getResourceFilename("bin", "evergreen"));
        environment.put("EVERGREEN_WORKSPACE_ROOT", FileUtilities.translateFilenameForShellUse(getRootDirectory()));
        PTextArea textArea = null;
        if (textWindow != null) {
            environment.put("EVERGREEN_CURRENT_FILENAME", FileUtilities.translateFilenameForShellUse(textWindow.getFilename()));
            
            // Humans number lines from 1, text components from 0.
            // FIXME: we should pass full selection information.
            textArea = textWindow.getTextArea();
            final int currentLineNumber = 1 + textArea.getLineOfOffset(textArea.getSelectionStart());
            environment.put("EVERGREEN_CURRENT_LINE_NUMBER", Integer.toString(currentLineNumber));
            
            putIfPlainText(environment, "EVERGREEN_CURRENT_WORD", ETextAction.getWordAtCaret(textWindow));
            if (textArea.getSelectionEnd() - textArea.getSelectionStart() < 1024) {
                putIfPlainText(environment, "EVERGREEN_CURRENT_SELECTION", textArea.getSelectedText());
            }
        }
        
        final EErrorsWindow errorsWindow = createErrorsWindow("Command Output"); // FIXME: be more specific.
        return new ShellCommand(textArea, errorsWindow, directory, command, environment, inputDisposition, outputDisposition);
    }
    
    // Environment variables are C strings, so we can't include NUL bytes.
    public static void putIfPlainText(Map<String, String> environment, String name, String value) {
        if (value.indexOf('\u0000') != -1) {
            return;
        }
        environment.put(name, value);
    }
}
