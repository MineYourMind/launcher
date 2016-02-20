/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.dialog;

import com.google.common.base.Strings;
import com.skcraft.concurrency.ObservableFuture;
import com.skcraft.launcher.Instance;
import com.skcraft.launcher.InstanceList;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.launch.LaunchListener;
import com.skcraft.launcher.swing.*;
import com.skcraft.launcher.util.Environment;
import com.skcraft.launcher.util.Platform;
import com.skcraft.launcher.util.SharedLocale;
import com.skcraft.launcher.util.SwingExecutor;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.java.Log;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.logging.Level;

import static com.skcraft.launcher.util.SharedLocale.tr;

/**
 * The main launcher frame.
 */
@Log
public class LauncherFrame extends JFrame {

    private final Launcher launcher;

    @Getter
    private final InstanceTable instancesTable = new InstanceTable();
    private final InstanceTableModel instancesModel;
    @Getter
    private final JScrollPane instanceScroll = new JScrollPane(instancesTable);

    private TableRowSorter<TableModel> instanceSorter;
    private SearchBox searchBox = new SearchBox(SharedLocale.tr("launcher.search"));
    private JSplitPane instanceSplitPane;

    private WebpagePanel webView;
    private JSplitPane splitPane;
    private final JButton launchButton = new JButton(SharedLocale.tr("launcher.launch"));
    private final JButton refreshButton = new JButton(SharedLocale.tr("launcher.checkForUpdates"));
    private final JButton optionsButton = new JButton(SharedLocale.tr("launcher.options"));
    private final JButton selfUpdateButton = new JButton(SharedLocale.tr("launcher.updateLauncher"));
    private final JCheckBox updateCheck = new JCheckBox(SharedLocale.tr("launcher.downloadUpdates"));

    /**
     * Create a new frame.
     *
     * @param launcher the launcher
     */
    public LauncherFrame(@NonNull Launcher launcher) {
        super(tr("launcher.title", launcher.getVersion()));

        this.launcher = launcher;
        instancesModel = new InstanceTableModel(launcher.getInstances());

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(760, 450);
        setMinimumSize(new Dimension(400, 300));
        initComponents();
        setLocationRelativeTo(null);

        SwingHelper.setIconImage(this, Launcher.class, "icon.png");

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                loadInstances();
            }
        });
    }

    private void initComponents() {

        JPanel container = createContainerPanel();
        container.setLayout(new MigLayout("fill, insets dialog", "[][]push[][]", "[grow][]"));

        instanceSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, searchBox, instanceScroll);
        webView = createNewsPanel();
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, instanceSplitPane, webView);
        selfUpdateButton.setVisible(launcher.getUpdateManager().getPendingUpdate());

        launcher.getUpdateManager().addPropertyChangeListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if (evt.getPropertyName().equals("pendingUpdate")) {
                    selfUpdateButton.setVisible((Boolean) evt.getNewValue());

                }
            }
        });

        updateCheck.setSelected(true);
        instancesTable.setModel(instancesModel);
        launchButton.setFont(launchButton.getFont().deriveFont(Font.BOLD));

        instanceSplitPane.setDividerLocation(24);
        instanceSplitPane.setDividerSize(2);
        instanceSplitPane.setOpaque(false);
        SwingHelper.flattenJSplitPane(instanceSplitPane);

        splitPane.setDividerLocation(200);
        splitPane.setDividerSize(4);
        splitPane.setOpaque(false);
        container.add(splitPane, "grow, wrap, span 5, gapbottom unrel");
        SwingHelper.flattenJSplitPane(splitPane);
        container.add(refreshButton);
        container.add(updateCheck);
        container.add(selfUpdateButton);
        container.add(optionsButton);
        container.add(launchButton);

        add(container, BorderLayout.CENTER);

        instancesModel.addTableModelListener(new TableModelListener() {
            @Override
            public void tableChanged(TableModelEvent e) {
                if (instancesTable.getRowCount() > 0) {
                    instancesTable.setRowSelectionInterval(0, 0);
                }
            }
        });

        instancesTable.addMouseListener(new DoubleClickToButtonAdapter(launchButton));

        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                loadInstances();
                launcher.getUpdateManager().checkForUpdate();
            }
        });

        selfUpdateButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                launcher.getUpdateManager().performUpdate(LauncherFrame.this);
            }
        });

        optionsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showOptions();
            }
        });

        launchButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                launch();
            }
        });

        instancesTable.addMouseListener(new PopupMouseAdapter() {
            @Override
            protected void showPopup(MouseEvent e) {
                int index = instancesTable.rowAtPoint(e.getPoint());
                int realIndex = instancesTable.convertRowIndexToModel(index);
                Instance selected = null;
                if (realIndex >= 0) {
                    instancesTable.setRowSelectionInterval(index, index);
                    selected = launcher.getInstances().get(realIndex);
                }
                popupInstanceMenu(e.getComponent(), e.getX(), e.getY(), selected);
            }
        });

        checkAndValidateJavaVersionAndSettings();
        tableSortFilter();

    }

    private void tableSortFilter() {
        instanceSorter = new TableRowSorter<TableModel>(instancesTable.getModel());
        instancesTable.setRowSorter(instanceSorter);

        searchBox.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                String text = searchBox.getText();

                if (text.trim().length() == 0 || text.equals(SharedLocale.tr("launcher.search"))) {
                    instanceSorter.setRowFilter(null);
                } else {
                    instanceSorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                String text = searchBox.getText();

                if (text.trim().length() == 0 || text.equals(SharedLocale.tr("launcher.search"))) {
                    instanceSorter.setRowFilter(null);
                } else {
                    instanceSorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                throw new UnsupportedOperationException("Not supported.");
            }
        });
    }

    /*
    * Compares the java runtime bit version with the operation's bit version and warns in case of mismatch about performance impact. Additionally warns about too high ram settings in relation with 32 bit.
    * If the operation system is a mac further steps are being taken in order to dodge the systems default java version which is outdated (1.6).
    */

    protected void checkAndValidateJavaVersionAndSettings() {

        boolean customJvmPath = false;

        if (!Strings.isNullOrEmpty(launcher.getConfig().getJvmPath())) {
            customJvmPath = true;
        }

        if (!customJvmPath && Environment.getInstance().getPlatform() == Platform.MAC_OS_X) {
            File customJava = new File("/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home/bin/java");
            if (customJava.exists() && customJava.canExecute()) {
                launcher.getConfig().setJvmPath("/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home");
                customJvmPath = true;
                log.log(Level.INFO, "Mac and custom java detected, setting corresponding jvm path.");
            }
        }

        if (!customJvmPath && Environment.getRuntimeJavaVersionMajor() < 1.7) {
            // Custom button text
            Object[] options = {"Yes, please", "No, thanks"};

            int n = JOptionPane
                    .showOptionDialog(
                            this,
                            "We detected that you are running an old version of Java which is no longer supported by all mod-packs.\nWe highly recommend to install Java 1.8.",
                            "WARNING", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, // no custom Icon
                            options, // the titles of buttons
                            options[0]); // default button title);

            try {
                if (n == 0) {
                    URL url = new URL("http://mym.li/java");
                    openWebpage(url);
                    System.exit(0);
                }
            } catch (MalformedURLException e1) {
                log.log(Level.SEVERE, "Malformed URL has occurred!", e1);
            }
        }

        Integer maxMem = launcher.getConfig().getMaxMemory();
        Integer permGen = launcher.getConfig().getPermGen();

        if (Environment.getInstance().getJavaBits().equals("32") && Environment.getInstance().getArchBits().equals("64")) {
            // Custom button text
            Object[] options = { "Yes, please", "No, thanks" };

            int n = JOptionPane
                    .showOptionDialog(
                            this,
                            "We detected that you are running Java 32bit on a 64 bit System.\nWe highly recommend to install/update Java 64bit for better performance.",
                            "WARNING", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, // no custom Icon
                            options, // the titles of buttons
                            options[0]); // default button title);

            try {
                if (n == 0) {
                    URL url = new URL("http://mym.li/java");
                    openWebpage(url);
                    System.exit(0);
                }
            } catch (MalformedURLException e1) {
                log.log(Level.SEVERE, "Malformed URL has occurred!", e1);
            }
        }
        else if (Environment.getInstance().getArchBits().equals("32") && (maxMem > 1244 || permGen > 128)) {
            // Custom button text
            Object[] options = { "Yes, show me how", "No, thanks" };

            int n = JOptionPane
                    .showOptionDialog(
                            this,
                            "We detected that you are running a 32 Bit System.\nIn order to use the launcher properly you need to change some settings.",
                            "WARNING", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, // no custom Icon
                            options, // the titles of buttons
                            options[0]); // default button title);

            try {
                if (n == 0) {
                    URL url = new URL("http://mym.li/32bit");
                    openWebpage(url);
                }
            } catch (MalformedURLException e1) {
                log.log(Level.SEVERE, "Malformed URL has occurred!", e1);
            }
        }

        log.log(Level.INFO, "JAVA VERSION: " + System.getProperty("sun.arch.data.model") + "Bit");
    }

    protected JPanel createContainerPanel() {
        return new JPanel();
    }

    /**
     * Return the news panel.
     *
     * @return the news panel
     */
    protected WebpagePanel createNewsPanel() {
        return WebpagePanel.forURL(launcher.getNewsURL(), false);
    }

    /**
     * Popup the menu for the instances.
     *
     * @param component the component
     * @param x mouse X
     * @param y mouse Y
     * @param selected the selected instance, possibly null
     */
    private void popupInstanceMenu(Component component, int x, int y, final Instance selected) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem menuItem;

        final ImageIcon installIcon;
        final ImageIcon launchIcon;
        final ImageIcon openFolderIcon;
        final ImageIcon openResourcePacksIcon;
        final ImageIcon openScreenshotsIcon;
        final ImageIcon deleteFilesIcon;
        final ImageIcon hardForceUpdateIcon;
        final ImageIcon forceUpdateIcon;

        if (selected != null) {
            installIcon = new ImageIcon(SwingHelper.readIconImage(Launcher.class, "installIcon.png"));
            launchIcon = new ImageIcon(SwingHelper.readIconImage(Launcher.class, "launchIcon.png"));

            menuItem = new JMenuItem(!selected.isLocal() ? "Install" : "Launch", !selected.isLocal() ? installIcon : launchIcon);
            menuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    launch();
                }
            });
            popup.add(menuItem);

            if (selected.isLocal()) {
                popup.addSeparator();

                openFolderIcon = new ImageIcon(SwingHelper.readIconImage(Launcher.class, "openFolderIcon.png"));

                menuItem = new JMenuItem(SharedLocale.tr("instance.openFolder"), openFolderIcon);
                menuItem.addActionListener(ActionListeners.browseDir(
                        LauncherFrame.this, selected.getContentDir(), true));
                popup.add(menuItem);

                /**
                menuItem = new JMenuItem(SharedLocale.tr("instance.openSaves"));
                menuItem.addActionListener(ActionListeners.browseDir(
                        LauncherFrame.this, new File(selected.getContentDir(), "saves"), true));
                popup.add(menuItem);
                 **/

                openResourcePacksIcon = new ImageIcon(SwingHelper.readIconImage(Launcher.class, "openResourcePacksIcon.png"));

                menuItem = new JMenuItem(SharedLocale.tr("instance.openResourcePacks"), openResourcePacksIcon);
                menuItem.addActionListener(ActionListeners.browseDir(
                        LauncherFrame.this, new File(selected.getContentDir(), "resourcepacks"), true));
                popup.add(menuItem);

                openScreenshotsIcon = new ImageIcon(SwingHelper.readIconImage(Launcher.class, "openScreenshotsIcon.png"));

                menuItem = new JMenuItem(SharedLocale.tr("instance.openScreenshots"), openScreenshotsIcon);
                menuItem.addActionListener(ActionListeners.browseDir(
                        LauncherFrame.this, new File(selected.getContentDir(), "screenshots"), true));
                popup.add(menuItem);

                /**
                menuItem = new JMenuItem(SharedLocale.tr("instance.copyAsPath"));
                menuItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        File dir = selected.getContentDir();
                        dir.mkdirs();
                        SwingHelper.setClipboard(dir.getAbsolutePath());
                    }
                });
                popup.add(menuItem);
                 **/

                popup.addSeparator();

                forceUpdateIcon = new ImageIcon(SwingHelper.readIconImage(Launcher.class, "forceUpdateIcon.png"));

                menuItem = new JMenuItem(selected.isUpdatePending() ? SharedLocale.tr("instance.update") : SharedLocale.tr("instance.forceUpdate"), forceUpdateIcon);
                menuItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                    	doUpdate(selected);
                    }
                });
                popup.add(menuItem);

                hardForceUpdateIcon = new ImageIcon(SwingHelper.readIconImage(Launcher.class, "hardForceUpdateIcon.png"));

                menuItem = new JMenuItem(SharedLocale.tr("instance.hardForceUpdate"), hardForceUpdateIcon);
                menuItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        confirmHardUpdate(selected);
                    }
                });
                popup.add(menuItem);

                deleteFilesIcon = new ImageIcon(SwingHelper.readIconImage(Launcher.class, "deleteFilesIcon.png"));

                menuItem = new JMenuItem(SharedLocale.tr("instance.deleteFiles"), deleteFilesIcon);
                menuItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        confirmDelete(selected);
                    }
                });
                popup.add(menuItem);
            }

            //popup.addSeparator();
        }

        /**
        menuItem = new JMenuItem(SharedLocale.tr("launcher.refreshList"));
        menuItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                loadInstances();
            }
        });
        popup.add(menuItem);
         **/

        popup.show(component, x, y);

    }


    public void openWebpage(URI uri) {
        Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
        if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
            try {
                desktop.browse(uri);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void openWebpage(URL url) {
        try {
            openWebpage(url.toURI());
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    private void confirmDelete(Instance instance) {
        if (!SwingHelper.confirmDialog(this,
                tr("instance.confirmDelete", instance.getTitle()), SharedLocale.tr("confirmTitle"))) {
            return;
        }

        ObservableFuture<Instance> future = launcher.getInstanceTasks().delete(this, instance);

        // Update the list of instances after updating
        future.addListener(new Runnable() {
            @Override
            public void run() {
                loadInstances();
            }
        }, SwingExecutor.INSTANCE);
    }

    private void confirmHardUpdate(Instance instance) {
        if (!SwingHelper.confirmDialog(this, SharedLocale.tr("instance.confirmHardUpdate"), SharedLocale.tr("confirmTitle"))) {
            return;
        }

        ObservableFuture<Instance> future = launcher.getInstanceTasks().hardUpdate(this, instance);

        // Update the list of instances after updating
        future.addListener(new Runnable() {
            @Override
            public void run() {
                launch();
                instancesModel.update();
            }
        }, SwingExecutor.INSTANCE);
    }

    private void loadInstances() {
        ObservableFuture<InstanceList> future = launcher.getInstanceTasks().reloadInstances(this);

        future.addListener(new Runnable() {
            @Override
            public void run() {
                instancesModel.update();
                if (instancesTable.getRowCount() > 0) {
                    instancesTable.setRowSelectionInterval(0, 0);
                }
                requestFocus();
            }
        }, SwingExecutor.INSTANCE);

        ProgressDialog.showProgress(this, future, SharedLocale.tr("launcher.checkingTitle"), SharedLocale.tr("launcher.checkingStatus"));
        SwingHelper.addErrorDialogCallback(this, future);
    }

    private void showOptions() {
        ConfigurationDialog configDialog = new ConfigurationDialog(this, launcher);
        configDialog.setVisible(true);
    }

    private void launch() {
        boolean permitUpdate = updateCheck.isSelected();
        Instance instance = launcher.getInstances().get(instancesTable.convertRowIndexToModel(instancesTable.getSelectedRow()));

        launcher.getLaunchSupervisor().launch(this, instance, permitUpdate, new LaunchListenerImpl(this));
    }

    private void doUpdate(final Instance selected) {
        selected.setUpdatePending(true);
        Instance instance = launcher.getInstances().get(instancesTable.convertRowIndexToModel(instancesTable.getSelectedRow()));
        launcher.getLaunchSupervisor().update(this, instance, new LaunchListenerImpl(this));
        instancesModel.update();
    }

    private static class LaunchListenerImpl implements LaunchListener {
        private final WeakReference<LauncherFrame> frameRef;
        private final Launcher launcher;

        private LaunchListenerImpl(LauncherFrame frame) {
            this.frameRef = new WeakReference<LauncherFrame>(frame);
            this.launcher = frame.launcher;
        }

        @Override
        public void instancesUpdated() {
            LauncherFrame frame = frameRef.get();
            if (frame != null) {
                frame.instancesModel.update();
            }
        }

        @Override
        public void gameStarted() {
            LauncherFrame frame = frameRef.get();
            if (frame != null) {
                frame.dispose();
            }
        }

        @Override
        public void gameClosed() {
            launcher.showLauncherWindow();
        }
    }
}