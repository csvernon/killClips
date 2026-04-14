package com.killClips;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class KillClipsPanel extends PluginPanel
{
    private static final int MAX_CLIPS = 50;
    private static final Color KILL_COLOR = new Color(230, 200, 50);
    private static final Color KILL_HOVER = new Color(255, 225, 80);
    private static final Color DEATH_COLOR = new Color(220, 60, 60);
    private static final Color DEATH_HOVER = new Color(245, 90, 90);
    private static final Color DEFAULT_LINK = new Color(93, 173, 226);
    private static final Color DEFAULT_HOVER = new Color(133, 193, 233);
    private static final Color DELETE_COLOR = new Color(180, 60, 60);
    private static final Color DELETE_HOVER = new Color(220, 80, 80);

    private static final Path SAVE_FILE = RuneLite.RUNELITE_DIR.toPath().resolve("kill-clips").resolve("clips.json");

    private final Gson gson;
    private final JPanel clipList;
    private final List<ClipEntry> clips = new ArrayList<>();

    public KillClipsPanel(Gson gson)
    {
        super(false);
        this.gson = gson;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);
        header.setBorder(new EmptyBorder(10, 10, 5, 10));

        JLabel title = new JLabel("Kill Clips");
        title.setFont(new Font("Arial", Font.BOLD, 16));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        header.add(title);

        header.add(Box.createVerticalStrut(5));

        JLabel subtitle = new JLabel("Recent uploads");
        subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        subtitle.setFont(subtitle.getFont().deriveFont(11f));
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        header.add(subtitle);

        header.add(Box.createVerticalStrut(10));

        JPanel linksRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        linksRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        linksRow.add(makeNavLink("Streamable", "https://streamable.com/"));
        linksRow.add(makeNavLink("Local clips", null));
        linksRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        header.add(linksRow);

        add(header, BorderLayout.NORTH);

        clipList = new JPanel();
        clipList.setLayout(new BoxLayout(clipList, BoxLayout.Y_AXIS));
        clipList.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JScrollPane scroll = new JScrollPane(clipList);
        scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.setBorder(new EmptyBorder(5, 10, 10, 10));
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(scroll, BorderLayout.CENTER);

        loadClips();
    }

    public void addClip(String description, String url)
    {
        SwingUtilities.invokeLater(() ->
        {
            ClipEntry entry = new ClipEntry(description, url);
            clips.add(0, entry);

            while (clips.size() > MAX_CLIPS)
            {
                clips.remove(clips.size() - 1);
            }

            rebuildList();
            saveClips();
        });
    }

    private void removeClip(int index)
    {
        if (index >= 0 && index < clips.size())
        {
            clips.remove(index);
            rebuildList();
            saveClips();
        }
    }

    private void rebuildList()
    {
        clipList.removeAll();

        for (int i = 0; i < clips.size(); i++)
        {
            ClipEntry entry = clips.get(i);
            clipList.add(buildClipRow(entry, i));
        }

        clipList.revalidate();
        clipList.repaint();
    }

    private JPanel buildClipRow(ClipEntry entry, int index)
    {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setBorder(new EmptyBorder(3, 0, 3, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        Color baseColor;
        Color hoverColor;
        if (entry.description != null && entry.description.contains("- kill -"))
        {
            baseColor = KILL_COLOR;
            hoverColor = KILL_HOVER;
        }
        else if (entry.description != null && entry.description.contains("- death -"))
        {
            baseColor = DEATH_COLOR;
            hoverColor = DEATH_HOVER;
        }
        else
        {
            baseColor = DEFAULT_LINK;
            hoverColor = DEFAULT_HOVER;
        }

        JLabel link = new JLabel(entry.description);
        link.setForeground(baseColor);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setFont(link.getFont().deriveFont(12f));
        link.setToolTipText(entry.url);
        link.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                LinkBrowser.browse(entry.url);
            }

            @Override
            public void mouseEntered(MouseEvent e)
            {
                link.setForeground(hoverColor);
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                link.setForeground(baseColor);
            }
        });
        row.add(link, BorderLayout.CENTER);

        JLabel deleteBtn = new JLabel("\u2716");
        deleteBtn.setForeground(DELETE_COLOR);
        deleteBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        deleteBtn.setFont(deleteBtn.getFont().deriveFont(11f));
        deleteBtn.setBorder(new EmptyBorder(0, 4, 0, 2));
        deleteBtn.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                removeClip(index);
            }

            @Override
            public void mouseEntered(MouseEvent e)
            {
                deleteBtn.setForeground(DELETE_HOVER);
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                deleteBtn.setForeground(DELETE_COLOR);
            }
        });
        row.add(deleteBtn, BorderLayout.EAST);

        return row;
    }

    private JLabel makeNavLink(String text, String url)
    {
        JLabel label = new JLabel(text);
        label.setForeground(DEFAULT_LINK);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setFont(label.getFont().deriveFont(11f));
        label.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (url != null)
                {
                    LinkBrowser.browse(url);
                }
                else
                {
                    File videosDir = new File(RuneLite.RUNELITE_DIR, "videos");
                    if (!videosDir.exists())
                    {
                        videosDir.mkdirs();
                    }
                    LinkBrowser.open(videosDir.getAbsolutePath());
                }
            }

            @Override
            public void mouseEntered(MouseEvent e)
            {
                label.setForeground(DEFAULT_HOVER);
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                label.setForeground(DEFAULT_LINK);
            }
        });
        return label;
    }

    private void loadClips()
    {
        try
        {
            if (Files.exists(SAVE_FILE))
            {
                String json = Files.readString(SAVE_FILE, StandardCharsets.UTF_8);
                Type listType = new TypeToken<List<ClipEntry>>() {}.getType();
                List<ClipEntry> loaded = gson.fromJson(json, listType);
                if (loaded != null)
                {
                    clips.addAll(loaded);
                    rebuildList();
                }
            }
        }
        catch (Exception e)
        {
            log.warn("Failed to load saved clips", e);
        }
    }

    private void saveClips()
    {
        try
        {
            Files.createDirectories(SAVE_FILE.getParent());
            Files.writeString(SAVE_FILE, gson.toJson(clips), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            log.warn("Failed to save clips", e);
        }
    }

    private static class ClipEntry
    {
        String description;
        String url;

        ClipEntry() {}

        ClipEntry(String description, String url)
        {
            this.description = description;
            this.url = url;
        }
    }
}
