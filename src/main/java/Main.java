import org.jline.builtins.Completers;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TODO:
 * 3. (FUN) Create AI-native shell
 */

public class Main {
  static class ProcessRunner {
    private ProcessRunner(){}

    public record Result(
      int exitCode,
      List<String> stdout
    ){}

    public record CommandSpec(
      List<String> args,
      Map<String, String> environment,
      Path stdin,
      Path stdout,
      boolean appendStdout,
      Path stderr,
      boolean appendStderr
    ){}

    public static Result runCapture(List<String> args, Map<String, String> environment) {
      if (args == null || args.isEmpty()) throw new IllegalArgumentException("Args cannot be empty");
      try {
        ProcessBuilder builder = createBuilder(args, environment);

        Process process = builder.start();
        List<String> output;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))){
          output = reader.readAllLines();
        }

        int exitCode = process.waitFor();
        return new Result(exitCode, output);
      } catch (IOException e){
        return new Result(1, null);
      } catch (InterruptedException e){
        Thread.currentThread().interrupt();
        return new Result(1, null);
      }
    }

    public static Process start(CommandSpec spec) throws IOException {
      return configureBuilder(spec).start();
    }

    public static List<Process> startPipeline(List<CommandSpec> commands) throws IOException {
      if (commands == null || commands.isEmpty()) throw new IllegalArgumentException("Pipeline must contain at least one command");

      List<ProcessBuilder> builders = new ArrayList<>();
      for (int i = 0; i < commands.size(); i++){
        CommandSpec spec = commands.get(i);
        ProcessBuilder builder = createBuilder(spec.args, spec.environment);

        boolean isFirst = i == 0;
        boolean isLast = i == commands.size() - 1;

        if (isFirst) configureInput(builder, spec.stdin);
        else if (spec.stdin != null){
          throw new IllegalArgumentException("Only the first pipeline command can redirect stdin");
        }

        if (isLast) configureOutput(builder, spec.stdout, spec.appendStdout);
        else if (spec.stdout != null)
          throw new UnsupportedOperationException("Intermediate stdout redirection required custom pipe handling");

        configureError(builder, spec.stderr, spec.appendStderr);
        builders.add(builder);
      }

      return ProcessBuilder.startPipeline(builders);
    }

    private static ProcessBuilder createBuilder(List<String> args, Map<String, String> environment){
      if (args == null || args.isEmpty()) throw new IllegalArgumentException("Command args cannot be empty");
      ProcessBuilder builder = new ProcessBuilder(args);

      if (environment != null) builder.environment().putAll(environment);
      return builder;
    }

    private static ProcessBuilder configureBuilder(CommandSpec spec){
      ProcessBuilder builder = createBuilder(spec.args, spec.environment);
      configureInput(builder, spec.stdin);
      configureOutput(builder, spec.stdout, spec.appendStdout);
      configureError(builder, spec.stderr, spec.appendStderr);
      return builder;
    }

    private static void configureInput(ProcessBuilder builder, Path path){
      if (path != null) builder.redirectInput(path.toFile());
      else builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
    }

    private static void configureOutput(ProcessBuilder builder, Path path, boolean append){
      if (path == null){
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
      } else if (append){
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(path.toFile()));
      } else {
        builder.redirectOutput(ProcessBuilder.Redirect.to(path.toFile()));
      }
    }

    private static void configureError(ProcessBuilder builder, Path path, boolean append){
      if (path == null){
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
      } else if (append){
        builder.redirectError(ProcessBuilder.Redirect.appendTo(path.toFile()));
      } else {
        builder.redirectError(ProcessBuilder.Redirect.to(path.toFile()));
      }
    }
  }

  static final class ShellExecutor {
    private final JobManager jobManager;
    private final Function<String, String> executableResolver;

    public ShellExecutor(JobManager jobManager, Function<String, String> executableResolver) {
      this.jobManager = jobManager;
      this.executableResolver = executableResolver;
    }

    // returns: <int> exitcode
    public int execute(List<ParsedCommand> commands, boolean background) throws IOException, InterruptedException {
      if (commands == null || commands.isEmpty()) return 0;

      List<ProcessRunner.CommandSpec> specs = new ArrayList<>(commands.size());

      for (ParsedCommand command : commands) {
        ProcessRunner.CommandSpec spec = createCommandSpec(command);
        if (spec == null) return 127;
        specs.add(spec);
      }

      List<Process> processes = startProcesses(specs);

      if (background) {
        jobManager.register(processes, commands);
        return 0;
      }

      return waitForPipeline(processes);
    }

    private ProcessRunner.CommandSpec createCommandSpec(ParsedCommand command) {
      List<String> args = new ArrayList<>();

      if (BUILTIN_COMMANDS.contains(command.command)){
        args.add("./your_program.sh");
        args.add("-c");
        args.add(
          String.join(
            " ",
            command.command,
            String.join(" ", command.args)
          )
        );
      } else {
        String executable = executableResolver.apply(command.command);
        if (executable == null) {
          command.error.printStream.printf("%s: command not found%n", command.command);
          return null;
        }
        args.add(command.command);
        for (String arg : command.args) {
          if (arg.equals("&")) continue;
          args.add(arg);
        }
      }


      return new ProcessRunner.CommandSpec(
        List.copyOf(args),
        null,
        null,
        command.output.path,
        command.output.append,
        command.error.path,
        command.error.append
      );
    }

    private List<Process> startProcesses(List<ProcessRunner.CommandSpec> specs) throws IOException {
      if (specs.size() == 1) return List.of(ProcessRunner.start(specs.getFirst()));
      return ProcessRunner.startPipeline(specs);
    }

    private int waitForPipeline(List<Process> processes) throws InterruptedException {
      try {
        int exitCode = 0;

        for (Process process : processes) exitCode = process.waitFor();
        return exitCode;

      } catch (InterruptedException e) {

        for (Process process : processes) process.destroyForcibly();
        for (Process process : processes) {
          try {
            process.waitFor();
          } catch (InterruptedException _) {}
        }
        throw e;

      }
    }
  }

  public enum JobStatus {
    RUNNING,
    DONE
  }

  static class BackgroundJob {
    private final int jobNumber;
    private final List<Process> processes;
    private final List<ParsedCommand> commands;

    private volatile JobStatus status;

    public BackgroundJob(
      int jobNumber,
      List<Process> processes,
      List<ParsedCommand> commands
    ) {
      this.jobNumber = jobNumber;
      this.processes = List.copyOf(processes);
      this.commands = List.copyOf(commands);
      this.status = JobStatus.RUNNING;
    }

    public int jobNumber() {
      return jobNumber;
    }

    public List<Process> processes() {
      return processes;
    }

    public List<ParsedCommand> commands() {
      return commands;
    }

    public JobStatus status() {
      return status;
    }

    public boolean isRunning() {
      return status == JobStatus.RUNNING;
    }

    void markDone() {
      status = JobStatus.DONE;
    }
  }

  static class JobManager {
    private final NavigableMap<Integer, BackgroundJob> jobs = new TreeMap<>();

    public int register(List<Process> processes, List<ParsedCommand> commands){
      if (processes == null || processes.isEmpty())
        throw new IllegalArgumentException("A job must contain at least one process");

      if (commands == null || commands.isEmpty())
        throw new IllegalArgumentException("A job must contain at least one command");

      int jobNumber = jobs.isEmpty() ? 1: jobs.lastKey() + 1;

      BackgroundJob job = new BackgroundJob(jobNumber, processes, commands);

      jobs.put(jobNumber, job);

      System.out.printf("[%d] %d%n", jobNumber, processes.getFirst().pid());

      trackCompletion(jobNumber, processes);

      return jobNumber;

    }

    public void trackCompletion(int jobNumber, List<Process> processes){
      CompletableFuture<?>[] completions = processes.stream()
        .map(Process::onExit)
        .toArray(CompletableFuture<?>[]::new);

      CompletableFuture.allOf(completions).thenRun(() -> markDone(jobNumber));
    }

    private synchronized void markDone(int jobNumber){
      BackgroundJob job = jobs.get(jobNumber);
      if (job != null) job.markDone();
    }

    public synchronized void printJobs() {
      Integer currentJob = jobs.isEmpty() ? null : jobs.lastKey();
      Integer previousJob = currentJob == null ? null : jobs.lowerKey(currentJob);

      Iterator<BackgroundJob> iterator = jobs.values().iterator();
      while (iterator.hasNext()) {
        BackgroundJob job = iterator.next();
        char marker = getJobMarker(job.jobNumber(), currentJob, previousJob);
        String status = job.isRunning() ? "Running" : "Done";
        String command = formatCommand(job.commands(), job.isRunning());
        System.out.printf("[%d]%c  %-24s %s%n", job.jobNumber(), marker, status, command);
        if (!job.isRunning()) {
          iterator.remove();
        }
      }
    }

    private char getJobMarker(int jobNumber, Integer currentJob, Integer previousJob) {
      if (Integer.valueOf(jobNumber).equals(currentJob)) return '+';
      if (Integer.valueOf(jobNumber).equals(previousJob)) return '-';
      return ' ';
    }

    private String formatCommand(List<ParsedCommand> commands, boolean running) {
      List<String> commandParts = new ArrayList<>();

      for (ParsedCommand command : commands) {
        List<String> args = new ArrayList<>(command.args);

        if (!args.isEmpty() && args.getLast().equals("&")) {
          args.removeLast();
        }

        String formatted = command.command
          + (args.isEmpty() ? "" : " " + String.join(" ", args));

        commandParts.add(formatted);
      }

      String result = String.join(" | ", commandParts);

      return running ? result + " &" : result;
    }

    public synchronized void reapCompletedJobs() {
      Integer currentJob = jobs.isEmpty() ? null : jobs.lastKey();
      Integer previousJob = currentJob == null ? null : jobs.lowerKey(currentJob);

      Iterator<BackgroundJob> iterator = jobs.values().iterator();
      while (iterator.hasNext()) {
        BackgroundJob job = iterator.next();
        if (!job.isRunning()) {
          char marker = getJobMarker(job.jobNumber(), currentJob, previousJob);
          String status = job.isRunning() ? "Running" : "Done";
          String command = formatCommand(job.commands(), job.isRunning());
          System.out.printf("[%d]%c  %-24s %s%n", job.jobNumber(), marker, status, command);
          iterator.remove();
        }
      }
    }
  }

  static class KeywordHighlighter implements Highlighter {
    private static Pattern keywordPattern;
    KeywordHighlighter(Collection<String> keywords){
      buildKeywordPattern(keywords);
    }

    @SafeVarargs
    KeywordHighlighter(Collection<String>... keywordCollections){
      Set<String> keywords = new HashSet<>();
      for (var collection: keywordCollections) keywords.addAll(collection);
      buildKeywordPattern(keywords);
    }

    private static void buildKeywordPattern(Collection<String> keywords){
      StringBuilder patternBuilder = new StringBuilder("\\b(");
      for (String keyword: keywords){
        patternBuilder.append(keyword).append('|');
      }
      patternBuilder.setLength(patternBuilder.length() - 1);
      patternBuilder.append(")\\b");
      keywordPattern = Pattern.compile(patternBuilder.toString(), Pattern.CASE_INSENSITIVE);
    }

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
      AttributedStringBuilder builder = new AttributedStringBuilder();

      Matcher matcher = keywordPattern.matcher(buffer);
      int lastEnd = 0;

      while (matcher.find()) {
        builder.append(buffer.substring(lastEnd, matcher.start()));

        builder.styled(
          AttributedStyle.BOLD.foreground(AttributedStyle.GREEN),
          buffer.substring(matcher.start(), matcher.end()));

        lastEnd = matcher.end();
      }

      if (lastEnd < buffer.length()) {
        builder.append(buffer.substring(lastEnd));
      }

      return builder.toAttributedString();
    }
  }

  static class CompletionsTabWidget implements Widget {
    private final LineReader reader;
    private final Set<String> completions;
    private final Terminal terminal;
    private final Completers.FileNameCompleter fileNameCompleter;

    private Map<String, String> completionRegistory = null;

    private String previousPrefix = null;
    private boolean waitingForSecondTab = false;

    private final static Character BELL = '\007';

    public final static String REFERENCE = "completions-tab";
    public final static String KEY = "\t";

    CompletionsTabWidget(Terminal terminal, LineReader reader, Set<String> completions, Completers.FileNameCompleter fileNameCompleter) throws FileNotFoundException {
      this.terminal = terminal;
      this.reader = reader;
      this.completions = completions;
      this.fileNameCompleter = fileNameCompleter;
    }

    private void cleanup(){
      previousPrefix = null;
      waitingForSecondTab = false;
    }

    private void ringBell(){
      terminal.writer().print(BELL);
      terminal.writer().flush();
    }

    private static String longestCommonPrefix(List<String> matches){
      String prefix = matches.getFirst();
      for (String match: matches){
        while (!match.startsWith(prefix)){
          prefix = prefix.substring(0, prefix.length() - 1);
          if (prefix.isEmpty()) return "";
        }
      }

      return prefix;
    }

    private boolean completeFileName(String bufferText, int cursor){
      List<Candidate> candidates = new ArrayList<>();
      ParsedLine parsedLine = reader.getParser().parse(bufferText, cursor, Parser.ParseContext.COMPLETE);
      String prefix = parsedLine.word();

      fileNameCompleter.complete(reader, parsedLine, candidates);

      List<String> matches = candidates.stream()
        .map(Candidate::value)
        .filter(v -> v.startsWith(parsedLine.word()))
        .sorted()
        .toList();

      if (matches.isEmpty()){
        ringBell();
        cleanup();
        return true;
      }

      Buffer buffer = reader.getBuffer();

      if (matches.size() == 1){
        String match = matches.getFirst();
        buffer.write(match.substring(prefix.length()));
        if (!match.endsWith("/")) buffer.write(" ");
        cleanup();
        reader.callWidget(LineReader.REDISPLAY);
        return true;
      }

      String lcp = longestCommonPrefix(matches);
      if (!lcp.equals(prefix)){
        buffer.write(lcp.substring(prefix.length()));
        cleanup();
        reader.callWidget(LineReader.REDISPLAY);
        return true;
      }

      if (!waitingForSecondTab || !prefix.equals(previousPrefix)){
        ringBell();
        waitingForSecondTab = true;
        previousPrefix = prefix;
        return true;
      }

      cleanup();
      terminal.writer().println();
      terminal.writer().println(String.join(" ", matches));
      terminal.writer().flush();

      reader.callWidget(LineReader.REDRAW_LINE);
      reader.callWidget(LineReader.REDISPLAY);
      return true;
    }

    private boolean completeCustomScript(String bufferText, int cursor){
      ParsedLine parsedLine = reader.getParser().parse(bufferText, cursor, Parser.ParseContext.COMPLETE);
      List<String> parts = parsedLine.words();

      String command = parts.getFirst();
      String precedingWord = "";
      String wordToComplete = parts.getLast();
      if (parts.size() >= 2) precedingWord = parts.get(parts.size() - 2);

      if (completionRegistory != null && completionRegistory.containsKey(command)){
        String scriptPath = completionRegistory.get(command);
        List<String> processArgs = List.of(scriptPath, command, wordToComplete, precedingWord);
        Map<String, String> processEnvironment = Map.of(
          "COMP_LINE", bufferText,
          "COMP_POINT", String.valueOf(cursor)
        );

        var processResult = ProcessRunner.runCapture(
          processArgs,
          processEnvironment
        );

        List<String> completions = processResult.stdout();

        if (completions != null && !completions.isEmpty()){
          Buffer buffer = reader.getBuffer();

          if (completions.size() == 1){
            buffer.write(completions.getFirst().substring(wordToComplete.length()) + " ");
            reader.callWidget(LineReader.REDISPLAY);
            cleanup();
            return true;
          }

          List<String> matches = completions.stream().map(String::trim).sorted().toList();

          String lcp = longestCommonPrefix(matches);
          if (!lcp.equals(wordToComplete)){
            buffer.write(lcp.substring(wordToComplete.length()));
            cleanup();
            reader.callWidget(LineReader.REDISPLAY);
            return true;
          }

          if (!waitingForSecondTab || !previousPrefix.equals(wordToComplete)){
            ringBell();
            waitingForSecondTab = true;
            previousPrefix = wordToComplete;
            return true;
          }

          cleanup();
          terminal.writer().println();
          terminal.writer().println(String.join("  ", matches));
          terminal.writer().flush();

          reader.callWidget(LineReader.REDRAW_LINE);
          reader.callWidget(LineReader.REDISPLAY);
          return true;
        }
      }

      return false;
    }

    public void registerCompletions(Map<String, String> completionRegistry){
      this.completionRegistory = completionRegistry;
    }

    @Override
    public boolean apply() {
      Buffer buffer = reader.getBuffer();
      int cursor = buffer.cursor();
      String bufferText = buffer.toString();
      String prefix = buffer.substring(0, cursor);

      if (completeCustomScript(bufferText, cursor)) return true;

      if (prefix.chars().anyMatch(Character::isWhitespace)){
        //TODO: handle external commands
        completeFileName(bufferText, cursor);
        return true;
      }

      List<String> matches = completions.stream()
        .filter(name -> name.startsWith(prefix))
        .sorted()
        .toList();

      if (matches.isEmpty()){
        ringBell();
        cleanup();
        return true;
      }

      if (matches.size() == 1){
        String match = matches.getFirst();
        buffer.write(match.substring(prefix.length()) + " ");
        cleanup();
        reader.callWidget(LineReader.REDISPLAY);
        return true;
      }

      String lcp = longestCommonPrefix(matches);
      if (!lcp.equals(prefix)){
        buffer.write(lcp.substring(prefix.length()));
        cleanup();
        reader.callWidget(LineReader.REDISPLAY);
        return true;
      }

      if (!waitingForSecondTab || !prefix.equals(previousPrefix)){
        ringBell();
        previousPrefix = prefix;
        waitingForSecondTab = true;
        return true;
      }

      cleanup();
      terminal.writer().println();
      terminal.writer().println(String.join(" ", matches));
      terminal.writer().flush();

      reader.callWidget(LineReader.REDRAW_LINE);
      reader.callWidget(LineReader.REDISPLAY);
      return true;
    }
  }

  record ParsedCommand(
    String command,
    List<String> args,
    Settings output,
    Settings error
  ){
    record Settings(
      PrintStream printStream,
      Path path,
      boolean append
    ){}

    static ParsedCommand parse(String input) throws IOException {
      Path outputPath = null, errPath = null;
      boolean appendOutput = false, appendError = false;
      List<String> parts = split(input, -1);
      int i = 0;
      for (; i < parts.size(); i++){
        String token = parts.get(i);
        if (token.equals(">>") || token.equals("1>>") || token.equals(">") || token.equals("1>")){
          if (token.equals(">>") || token.equals("1>>")) appendOutput = true;
          outputPath = resolvePath(parts.get(i + 1));
          parts.remove(i + 1);
          parts.remove(i);
          break;
        }
        if (token.equals("2>") || token.equals("2>>")){
          if (token.equals("2>>")) appendError = true;
          errPath = resolvePath(parts.get(i + 1));
          parts.remove(i + 1);
          parts.remove(i);
          break;
        }
      }

      PrintStream out;
      if (outputPath == null) out = System.out;
      else {
        if (!Files.exists(outputPath)) Files.createFile(outputPath);
        out = new PrintStream(new FileOutputStream(outputPath.toFile(), appendOutput), true);
      }

      PrintStream err;
      if (errPath == null) err = System.err;
      else {
        if (!Files.exists(errPath)) Files.createFile(errPath);
        err = new PrintStream(new FileOutputStream(errPath.toFile(), appendError), true);
      }

      return new ParsedCommand(
        parts.getFirst(),
        parts
          .subList(1, parts.size())
          .stream()
          .map(Main::expandVariables)
          .filter(arg -> !arg.isEmpty())
          .toList(),
        new ParsedCommand.Settings(out, outputPath, appendOutput),
        new ParsedCommand.Settings(err, errPath, appendError)
      );
    }
  }

  private static final Set<String> BUILTIN_COMMANDS = Set.of("echo", "type", "exit", "pwd", "cd", "complete", "jobs", "history", "declare");
  private static final List<String> DIR_PATHS = new ArrayList<>();
  private static final Path HOME;
  private static Path HISTFILE = null;
  private static final Map<String, String> EXECUTABLE_PATHS = new HashMap<>();
  private static final History history = new DefaultHistory();
  private static final KeywordHighlighter keywordHighlighter = new KeywordHighlighter(BUILTIN_COMMANDS);
  private static final Map<String, String> completeCmdRegistry = new HashMap<>();
  private static final Map<String, String> declareVarRegistry = new HashMap<>();
  private static final JobManager jobManager = new JobManager();
  private static final ShellExecutor shellExecutor = new ShellExecutor(jobManager, Main::searchExecutable);


  private static Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
  private static Terminal terminal = null;
  private static LineReader reader = null;
  private static int historyAppendPoint = 0;


  static {
    String path = System.getenv("PATH");
    if (path != null){
      String[] paths = path.split(File.pathSeparator);
      DIR_PATHS.addAll(Arrays.asList(paths));
    }

    // After getting DIR_PATHS, loads all the executable inside.
    loadExecutable();

    if (System.getenv("HOME") != null)
      HOME = Paths.get(System.getenv("HOME")).toAbsolutePath();
    else HOME = Paths.get(System.getProperty("user.home")).toAbsolutePath();

    loadHistory();
  }

  private static void loadHistory(){
    if (System.getenv("HISTFILE") != null){
      HISTFILE = Path.of(System.getenv("HISTFILE"));
      try(var lines = Files.lines(HISTFILE)) {
        lines.forEach(history::add);
      } catch (IOException _){}
    }
  }

  private static void saveHistory() {
    if (HISTFILE == null) return;
    try (var writer = Files.newBufferedWriter(HISTFILE)) {
      for (var entry: history){
        writer.write(entry.line() + "\n");
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String searchExecutable(String command, String dirPath){
    File file = new File(dirPath, command);
    if (file.isFile() && Files.isExecutable(file.toPath()))
      return file.getPath();
    return null;
  }

  private static String searchExecutable(String command){
    if (EXECUTABLE_PATHS.containsKey(command)) return EXECUTABLE_PATHS.get(command);

    for (String dirPath: DIR_PATHS){
      File file = new File(dirPath, command);
      if (file.isFile() && Files.isExecutable(file.toPath()))
        return file.getPath();
    }
    return null;
  }

  private static void loadExecutable(){
    for (String dirPath: DIR_PATHS){
      File dir = new File(dirPath);

      if (!dir.isDirectory()) continue;

      File[] files = dir.listFiles();
      if (files == null) continue;

      for (File file: files){
        if (file.isFile() && Files.isExecutable(file.toPath())){
          EXECUTABLE_PATHS.putIfAbsent(file.getName(), file.getPath());
        }
      }
    }
  }

  private static Path resolvePath(String path){
    if (path.equals("~")) return HOME;
    if (path.startsWith("~/")) return HOME.resolve(path.substring(2)).normalize();
    return cwd.resolve(path).normalize();
  }

  /** limit: > 0: collect <size>, <= 0: collect all */
  private static List<String> split(String content, int limit){
    if (limit == 1) return List.of(content);

    List<String> parts = new ArrayList<>();
    StringBuilder part = new StringBuilder();

    boolean isInsideDoubleQuotes = false, isInsideSingleQuotes = false, isBackslash = false;
    int count = limit > 0 ? limit - 1: Integer.MAX_VALUE;
    int i = 0;

    for (; i < content.length() && count > 0; i++){
      char ch = content.charAt(i);
      if (isBackslash){
        if (isInsideDoubleQuotes && !"n\"\\$`".contains(String.valueOf(ch))){
          part.append('\\');
        }
        part.append(ch);
        isBackslash = false;
      }
      else if (ch == '\\' && !isInsideSingleQuotes){
        isBackslash = true;
      }
      else if (ch == '"' && !isInsideSingleQuotes){
        isInsideDoubleQuotes = !isInsideDoubleQuotes;
      }
      else if (ch == '\'' && !isInsideDoubleQuotes){
        isInsideSingleQuotes = !isInsideSingleQuotes;
      }
      else if (ch == ' ' && !(isInsideDoubleQuotes || isInsideSingleQuotes)){
        if (!part.isEmpty()){
          parts.add(part.toString());
          count--;
          part.setLength(0);
        }
      }
      else part.append(ch);
    }

    if (count > 0 && !part.isEmpty()) parts.add(part.toString());
    if (i < content.length()) parts.add(content.substring(i));
    return parts;
  }

  private static void handleTypeCommand(ParsedCommand parsedCommand){
    if (parsedCommand.args.isEmpty()) parsedCommand.output.printStream.println();
    String command = parsedCommand.args.getFirst();
    if (BUILTIN_COMMANDS.contains(command))
      parsedCommand.output.printStream.printf("%s is a shell builtin\n", command);
    else {
      String fullPath = searchExecutable(command);
      if (fullPath != null)
        parsedCommand.output.printStream.printf("%s is %s\n", command, fullPath);
      else parsedCommand.error.printStream.printf("%s: not found\n", command);
    }
  }

  private static void handleChangeDirectoryCommand(ParsedCommand parsedCommand){
    Path targetDir;
    String path = "";
    if (parsedCommand.args.isEmpty()) targetDir = HOME;
    else {
      path = parsedCommand.args.getFirst();
      targetDir = resolvePath(path);
    }
    if (Files.isDirectory(targetDir)) cwd = targetDir;
    else parsedCommand.output.printStream.printf("cd: %s: No such file or directory\n", path);
  }

  private static void handleEchoCommand(ParsedCommand parsedCommand){
    List<String> parts = parsedCommand.args;
    parsedCommand.output.printStream.println(
      String.join(" ", parts)
    );
  }

  private static void handleCatCommand(ParsedCommand parsedCommand) throws IOException {
    List<String> fileNames = parsedCommand.args;
    for (String fileName: fileNames){
      Files.copy(resolvePath(fileName), parsedCommand.output.printStream);
    }
    parsedCommand.output.printStream.flush();
  }

  private static void handleCompleteCommand(ParsedCommand parsedCommand) {
    List<String> args = parsedCommand.args;
    String flag = args.getFirst();

    if (flag.equals("-C") && args.size() == 3){
      String scriptPath = args.get(1);
      String scriptKey = args.get(2);
      completeCmdRegistry.put(scriptKey, scriptPath);
    }

    if (flag.equals("-p") && args.size() == 2){
      String scriptKey = args.get(1);
      if (completeCmdRegistry.containsKey(scriptKey)){
        String scriptPath = completeCmdRegistry.get(scriptKey);
        parsedCommand.output.printStream.printf("complete -C '%s' %s\n", scriptPath, scriptKey);
      } else {
        parsedCommand.error.printStream.printf("complete: %s: no completion specification\n", scriptKey);
      }
    }

    if (flag.equals("-r") && args.size() == 2){
      String scriptKey = args.get(1);
      completeCmdRegistry.remove(scriptKey);
    }
  }

  private static void handleJobsCommand(ParsedCommand parsedCommand){
    jobManager.printJobs();
  }

  private static void handleUnknownCommand(ParsedCommand parsedCommand) throws IOException, InterruptedException {
    String fullPath = searchExecutable(parsedCommand.command);
    boolean isBackgroundJob = false;

    if (fullPath != null) {
      List<String> processArgs = new ArrayList<>();
      processArgs.add(parsedCommand.command);
      processArgs.addAll(parsedCommand.args);

      if (processArgs.getLast().equals("&")){
        isBackgroundJob = true;
        processArgs.removeLast();
      }

      Process process = ProcessRunner.start(
        new ProcessRunner.CommandSpec(
          processArgs,
          null,
          null,
          parsedCommand.output.path,
          parsedCommand.output.append,
          parsedCommand.error.path,
          parsedCommand.error.append
        )
      );

      if (!isBackgroundJob) process.waitFor();
      else jobManager.register(List.of(process), List.of(parsedCommand));

    } else parsedCommand.error.printStream.printf("%s: command not found\n", parsedCommand.command);
  }

  private static void handleHistoryCommand(ParsedCommand parsedCommand) throws IOException {
    if (reader == null) return;
    int size = Integer.MAX_VALUE;

    if (!parsedCommand.args.isEmpty()){
      String flag = parsedCommand.args.getFirst();

      if (flag.equals("-r")){
        Path filePath = Path.of(parsedCommand.args.getLast());
        if (Files.exists(filePath)){
          List<String> lines = Files.readAllLines(filePath);
          lines.forEach(reader.getHistory()::add);
        }
        return;
      }

      if (flag.equals("-w")){
        Path filePath = Path.of(parsedCommand.args.getLast());
        List<String> commands = new ArrayList<>();
        history.iterator().forEachRemaining((e) -> commands.add(e.line()));
        Files.write(filePath, commands, StandardCharsets.UTF_8);
        return;
      }

      if (flag.equals("-a")){
        Path filePath = Path.of(parsedCommand.args.getLast());
        List<String> commands = new ArrayList<>();
        history.iterator().forEachRemaining((e) -> commands.add(e.line()));
        Files.write(
          filePath,
          commands.subList(historyAppendPoint, commands.size()),
          StandardCharsets.UTF_8,
          StandardOpenOption.APPEND
        );
        historyAppendPoint = commands.size();
        return;
      }

      if (Character.isDigit(flag.charAt(0))){
        size = Integer.parseInt(flag);
      }
    }

    int skip = Math.max(0, history.size() - size);
    for (int i = 0; i < history.size(); i++){
      if (--skip >= 0) continue;
      parsedCommand.output.printStream.printf("    %d  %s\n", i + 1, history.get(i));
    }
  }

  private static void handleExitCommand(){
    saveHistory();
    System.exit(0);
  }

  private static boolean isValidIdentifier(String key){
    return key.matches("[A-Za-z_][0-9A-Za-z_]*");
  }

  private static String expandVariables(String arg){
    Pattern pattern = Pattern.compile("\\$\\{([^}]+)}|\\$([A-Za-z_][0-9A-Za-z_]*)");
    Matcher matcher = pattern.matcher(arg);
    StringBuilder result = new StringBuilder();
    while (matcher.find()){
      String varName = matcher.group(1) != null
        ? matcher.group(1)
        : matcher.group(2);
      String value = declareVarRegistry.getOrDefault(varName, "");
      matcher.appendReplacement(result, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static void handleDeclareCommand(ParsedCommand parsedCommand){
    if (!parsedCommand.args.isEmpty()){
      List<String> args = parsedCommand.args;
      String flag = args.getFirst();

      if (flag.equals("-p")){
        String key = args.getLast();
        if (!declareVarRegistry.containsKey(key))
          parsedCommand.error.printStream.printf("declare: %s: not found\n", key);
        else
          parsedCommand.output.printStream.printf("declare -- %s=\"%s\"\n",
            key,
            declareVarRegistry.get(key)
          );
      }

      if (flag.contains("=")){
        String[] keyValue = flag.split("=");
        if (!isValidIdentifier(keyValue[0])){
          parsedCommand.error.printStream.printf(
            "declare: `%s=%s': not a valid identifier\n",
            keyValue[0], keyValue[1]
          );
        }
        else declareVarRegistry.put(keyValue[0], keyValue[1]);
      }
    }
  }

  private static void handlePipelineCommands(List<ParsedCommand> parsedCommands) throws IOException, InterruptedException {
   shellExecutor.execute(parsedCommands, false);
  }

  private static void executeSingleCommand(ParsedCommand parsedCommand) throws Exception {
    switch (parsedCommand.command) {
      case "exit"     -> handleExitCommand();
      case "pwd"      -> System.out.println(cwd);
      case "echo"     -> handleEchoCommand(parsedCommand);
      case "type"     -> handleTypeCommand(parsedCommand);
      case "cd"       -> handleChangeDirectoryCommand(parsedCommand);
      case "complete" -> handleCompleteCommand(parsedCommand);
      case "jobs"     -> handleJobsCommand(parsedCommand);
      case "history"  -> handleHistoryCommand(parsedCommand);
      case "declare"  -> handleDeclareCommand(parsedCommand);
      default         -> handleUnknownCommand(parsedCommand);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length >= 2 && args[0].equals("-c")){
      executeSingleCommand(ParsedCommand.parse(args[1]));
      System.exit(0);
    }

    terminal = TerminalBuilder.builder()
      .system(true)
      .signalHandler(Terminal.SignalHandler.SIG_IGN)
      .build();

    Runtime.getRuntime().addShutdownHook(new Thread(Main::saveHistory));

    Completers.FileNameCompleter fileNameCompleter = new Completers.FileNameCompleter();

    reader = LineReaderBuilder.builder()
      .parser(new DefaultParser().escapeChars(new char[0]))
      .terminal(terminal)
      .highlighter(keywordHighlighter)
      .history(history)
        .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
        .option(LineReader.Option.HISTORY_BEEP, true)
      .completer(fileNameCompleter)
        .option(LineReader.Option.USE_FORWARD_SLASH, true)
      .build();

    Set<String> completions = new HashSet<>();
    completions.addAll(BUILTIN_COMMANDS);
    completions.addAll(EXECUTABLE_PATHS.keySet());

    CompletionsTabWidget completionsTabWidget = new CompletionsTabWidget(terminal, reader, completions, fileNameCompleter);
    reader.getWidgets().put(CompletionsTabWidget.REFERENCE, completionsTabWidget);
    reader.getKeyMaps().get(LineReader.MAIN)
      .bind(new Reference(CompletionsTabWidget.REFERENCE), CompletionsTabWidget.KEY);
    completionsTabWidget.registerCompletions(completeCmdRegistry);


    while(true){
      jobManager.reapCompletedJobs();

      // handle pipe
      
      String input = reader.readLine("$ ").trim();

      if (input.equalsIgnoreCase("exit")) break;

      if (input.contains("|")){
        String[] commands = input.split("\\|");
        List<ParsedCommand> parsedCommands = new ArrayList<>();
        for (String command: commands)
          parsedCommands.add(ParsedCommand.parse(command));
        handlePipelineCommands(parsedCommands);
      } else {
        executeSingleCommand(ParsedCommand.parse(input));
      }
    }
  }
}
