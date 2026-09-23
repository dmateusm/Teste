import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sistema de Gerenciamento de Biblioteca.
 *
 * Funcionalidades:
 *  - Cadastro de livros (com multiplos exemplares) e de membros
 *  - Emprestimo, devolucao e renovacao com regras de negocio
 *  - Calculo de multa por atraso
 *  - Busca por titulo, autor ou categoria
 *  - Persistencia em arquivo (serializacao Java)
 *  - Interface de console (menu)
 *
 * Execucao:  java BibliotecaApp.java   (Java 17 ou superior)
 */
public class BibliotecaApp {

    // =====================================================================
    // SECAO 1: EXCECOES DE DOMÍNIO
    // Exceções específicas deixam as falhas de regra de negócio explícitas
    // e permitem que a interface exiba mensagens claras ao usuário.
    // =====================================================================

    /** Exceção base para qualquer violação de regra da biblioteca. */
    static class BibliotecaException extends RuntimeException {
        BibliotecaException(String msg) { super(msg); }
    }

    /** Lançada quando uma entidade (livro, membro, empréstimo) não existe. */
    static class NaoEncontradoException extends BibliotecaException {
        NaoEncontradoException(String msg) { super(msg); }
    }

    /** Lançada ao tentar cadastrar algo que já existe (ex.: ISBN repetido). */
    static class DuplicadoException extends BibliotecaException {
        DuplicadoException(String msg) { super(msg); }
    }

    /** Lançada quando uma operação de empréstimo/renovação é proibida. */
    static class OperacaoNegadaException extends BibliotecaException {
        OperacaoNegadaException(String msg) { super(msg); }
    }

    // =====================================================================
    // SEÇÃO 2: MODELO DE DOMÍNIO
    // Todas as classes são Serializable para permitir a persistência.
    // =====================================================================

    /**
     * Tipo de membro. Cada tipo define seus próprios limites:
     * quantidade máxima de empréstimos simultâneos e prazo em dias.
     */
    enum TipoMembro {
        ALUNO(3, 14), PROFESSOR(8, 30), VISITANTE(1, 7);

        final int limiteEmprestimos;
        final int prazoDias;

        TipoMembro(int limiteEmprestimos, int prazoDias) {
            this.limiteEmprestimos = limiteEmprestimos;
            this.prazoDias = prazoDias;
        }
    }

    /** Livro do acervo. Controla o total de exemplares e os disponíveis. */
    static class Livro implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String isbn;
        private final String titulo;
        private final String autor;
        private final String categoria;
        private final int ano;
        private final int totalExemplares;
        private int disponiveis;

        Livro(String isbn, String titulo, String autor, String categoria, int ano, int totalExemplares) {
            // Validação no construtor garante que nunca existirá um livro inválido.
            if (isbn == null || isbn.isBlank()) throw new IllegalArgumentException("ISBN obrigatório.");
            if (titulo == null || titulo.isBlank()) throw new IllegalArgumentException("Título obrigatório.");
            if (totalExemplares < 1) throw new IllegalArgumentException("Deve haver ao menos 1 exemplar.");
            this.isbn = isbn.trim();
            this.titulo = titulo.trim();
            this.autor = autor == null ? "Desconhecido" : autor.trim();
            this.categoria = categoria == null ? "Geral" : categoria.trim();
            this.ano = ano;
            this.totalExemplares = totalExemplares;
            this.disponiveis = totalExemplares;
        }

        String getIsbn() { return isbn; }
        String getTitulo() { return titulo; }
        String getAutor() { return autor; }
        String getCategoria() { return categoria; }
        int getDisponiveis() { return disponiveis; }

        /** Retira um exemplar do estoque (empréstimo). */
        void retirarExemplar() {
            if (disponiveis <= 0) throw new OperacaoNegadaException("Sem exemplares disponíveis.");
            disponiveis--;
        }

        /** Devolve um exemplar ao estoque, sem ultrapassar o total. */
        void devolverExemplar() {
            if (disponiveis < totalExemplares) disponiveis++;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s - %s (%d) | %s | %d/%d disponíveis",
                    isbn, titulo, autor, ano, categoria, disponiveis, totalExemplares);
        }
    }

    /** Membro (usuário) da biblioteca. */
    static class Membro implements Serializable {
        private static final long serialVersionUID = 1L;

        private final long id;
        private final String nome;
        private final String email;
        private final TipoMembro tipo;

        Membro(long id, String nome, String email, TipoMembro tipo) {
            if (nome == null || nome.isBlank()) throw new IllegalArgumentException("Nome obrigatório.");
            // Validação simples de e-mail: suficiente para o escopo do sistema.
            if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
                throw new IllegalArgumentException("E-mail inválido.");
            this.id = id;
            this.nome = nome.trim();
            this.email = email.trim().toLowerCase();
            this.tipo = Objects.requireNonNull(tipo);
        }

        long getId() { return id; }
        String getNome() { return nome; }
        String getEmail() { return email; }
        TipoMembro getTipo() { return tipo; }

        @Override
        public String toString() {
            return String.format("#%d %s <%s> (%s)", id, nome, email, tipo);
        }
    }

    /** Registro de um empréstimo, do início até a devolução. */
    static class Emprestimo implements Serializable {
        private static final long serialVersionUID = 1L;
        static final int MAX_RENOVACOES = 2;

        private final long id;
        private final String isbn;
        private final long membroId;
        private final LocalDate dataEmprestimo;
        private LocalDate dataPrevista;
        private LocalDate dataDevolucao; // null enquanto estiver ativo
        private int renovacoes;

        Emprestimo(long id, String isbn, long membroId, LocalDate inicio, LocalDate prevista) {
            this.id = id;
            this.isbn = isbn;
            this.membroId = membroId;
            this.dataEmprestimo = inicio;
            this.dataPrevista = prevista;
        }

        long getId() { return id; }
        String getIsbn() { return isbn; }
        long getMembroId() { return membroId; }
        LocalDate getDataPrevista() { return dataPrevista; }
        int getRenovacoes() { return renovacoes; }
        boolean isAtivo() { return dataDevolucao == null; }

        /** Um empréstimo está atrasado se ativo e com prazo vencido. */
        boolean isAtrasado(LocalDate hoje) { return isAtivo() && hoje.isAfter(dataPrevista); }

        void encerrar(LocalDate data) { this.dataDevolucao = data; }

        void renovar(int dias) {
            this.dataPrevista = dataPrevista.plusDays(dias);
            this.renovacoes++;
        }

        @Override
        public String toString() {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            return String.format("Empréstimo #%d | ISBN %s | Membro #%d | Previsto: %s | Renovações: %d%s",
                    id, isbn, membroId, dataPrevista.format(f), renovacoes,
                    isAtivo() ? "" : " | Devolvido em " + dataDevolucao.format(f));
        }
    }

    // =====================================================================
    // SEÇÃO 3: SERVIÇO PRINCIPAL (REGRAS DE NEGÓCIO)
    // Concentra todas as regras; a interface apenas chama estes métodos.
    // Métodos são synchronized para segurança básica em uso concorrente.
    // =====================================================================

    static class Biblioteca implements Serializable {
        private static final long serialVersionUID = 1L;
        private static final BigDecimal MULTA_POR_DIA = new BigDecimal("1.00");

        private final Map<String, Livro> livros = new LinkedHashMap<>();
        private final Map<Long, Membro> membros = new LinkedHashMap<>();
        private final Map<Long, Emprestimo> emprestimos = new LinkedHashMap<>();
        private long proximoMembroId = 1;
        private long proximoEmprestimoId = 1;

        // ---------- Cadastros ----------

        synchronized Livro cadastrarLivro(String isbn, String titulo, String autor,
                                          String categoria, int ano, int exemplares) {
            Livro livro = new Livro(isbn, titulo, autor, categoria, ano, exemplares);
            if (livros.containsKey(livro.getIsbn()))
                throw new DuplicadoException("Já existe livro com o ISBN " + livro.getIsbn());
            livros.put(livro.getIsbn(), livro);
            return livro;
        }

        synchronized Membro cadastrarMembro(String nome, String email, TipoMembro tipo) {
            // Impede dois cadastros com o mesmo e-mail.
            boolean existe = membros.values().stream()
                    .anyMatch(m -> m.getEmail().equalsIgnoreCase(email == null ? "" : email.trim()));
            if (existe) throw new DuplicadoException("E-mail já cadastrado: " + email);
            Membro m = new Membro(proximoMembroId++, nome, email, tipo);
            membros.put(m.getId(), m);
            return m;
        }

        // ---------- Consultas ----------

        synchronized List<Livro> listarLivros() { return new ArrayList<>(livros.values()); }
        synchronized List<Membro> listarMembros() { return new ArrayList<>(membros.values()); }

        /** Busca livros cujo título, autor ou categoria contenha o termo (sem diferenciar maiúsculas). */
        synchronized List<Livro> buscar(String termo) {
            String t = termo == null ? "" : termo.toLowerCase().trim();
            return livros.values().stream()
                    .filter(l -> l.getTitulo().toLowerCase().contains(t)
                            || l.getAutor().toLowerCase().contains(t)
                            || l.getCategoria().toLowerCase().contains(t))
                    .collect(Collectors.toList());
        }

        synchronized List<Emprestimo> emprestimosAtivos() {
            return emprestimos.values().stream().filter(Emprestimo::isAtivo).collect(Collectors.toList());
        }

        synchronized List<Emprestimo> emprestimosAtrasados(LocalDate hoje) {
            return emprestimos.values().stream().filter(e -> e.isAtrasado(hoje)).collect(Collectors.toList());
        }

        // ---------- Operações de empréstimo ----------

        /**
         * Realiza um empréstimo aplicando as regras:
         *  1) livro e membro devem existir;
         *  2) deve haver exemplar disponível;
         *  3) membro não pode ter empréstimos atrasados;
         *  4) membro não pode exceder seu limite simultâneo.
         */
        synchronized Emprestimo emprestar(String isbn, long membroId, LocalDate hoje) {
            Livro livro = buscarLivro(isbn);
            Membro membro = buscarMembro(membroId);

            if (livro.getDisponiveis() == 0)
                throw new OperacaoNegadaException("Nenhum exemplar disponível de \"" + livro.getTitulo() + "\".");

            List<Emprestimo> ativosDoMembro = ativosDoMembro(membroId);
            if (ativosDoMembro.stream().anyMatch(e -> e.isAtrasado(hoje)))
                throw new OperacaoNegadaException("Membro possui empréstimo(s) em atraso.");
            if (ativosDoMembro.size() >= membro.getTipo().limiteEmprestimos)
                throw new OperacaoNegadaException("Limite de " + membro.getTipo().limiteEmprestimos
                        + " empréstimos simultâneos atingido.");
            if (ativosDoMembro.stream().anyMatch(e -> e.getIsbn().equals(livro.getIsbn())))
                throw new OperacaoNegadaException("Membro já está com um exemplar deste livro.");

            livro.retirarExemplar();
            Emprestimo e = new Emprestimo(proximoEmprestimoId++, livro.getIsbn(), membroId,
                    hoje, hoje.plusDays(membro.getTipo().prazoDias));
            emprestimos.put(e.getId(), e);
            return e;
        }

        /** Registra a devolução e retorna o valor da multa (zero se no prazo). */
        synchronized BigDecimal devolver(long emprestimoId, LocalDate hoje) {
            Emprestimo e = buscarEmprestimo(emprestimoId);
            if (!e.isAtivo()) throw new OperacaoNegadaException("Este empréstimo já foi devolvido.");
            BigDecimal multa = calcularMulta(e, hoje);
            e.encerrar(hoje);
            buscarLivro(e.getIsbn()).devolverExemplar();
            return multa;
        }

        /** Renova um empréstimo: limitado a 2 vezes e proibido se em atraso. */
        synchronized Emprestimo renovar(long emprestimoId, LocalDate hoje) {
            Emprestimo e = buscarEmprestimo(emprestimoId);
            if (!e.isAtivo()) throw new OperacaoNegadaException("Empréstimo já encerrado.");
            if (e.isAtrasado(hoje)) throw new OperacaoNegadaException("Empréstimo em atraso não pode ser renovado.");
            if (e.getRenovacoes() >= Emprestimo.MAX_RENOVACOES)
                throw new OperacaoNegadaException("Limite de renovações atingido.");
            e.renovar(buscarMembro(e.getMembroId()).getTipo().prazoDias);
            return e;
        }

        /** Multa = dias de atraso x valor diário. Nunca negativa. */
        BigDecimal calcularMulta(Emprestimo e, LocalDate ref) {
            long dias = ChronoUnit.DAYS.between(e.getDataPrevista(), ref);
            return dias > 0 ? MULTA_POR_DIA.multiply(BigDecimal.valueOf(dias)) : BigDecimal.ZERO;
        }

        // ---------- Auxiliares privados ----------

        private List<Emprestimo> ativosDoMembro(long membroId) {
            return emprestimos.values().stream()
                    .filter(e -> e.isAtivo() && e.getMembroId() == membroId)
                    .collect(Collectors.toList());
        }

        private Livro buscarLivro(String isbn) {
            Livro l = livros.get(isbn == null ? "" : isbn.trim());
            if (l == null) throw new NaoEncontradoException("Livro não encontrado: " + isbn);
            return l;
        }

        private Membro buscarMembro(long id) {
            Membro m = membros.get(id);
            if (m == null) throw new NaoEncontradoException("Membro não encontrado: #" + id);
            return m;
        }

        private Emprestimo buscarEmprestimo(long id) {
            Emprestimo e = emprestimos.get(id);
            if (e == null) throw new NaoEncontradoException("Empréstimo não encontrado: #" + id);
            return e;
        }
    }

    // =====================================================================
    // SEÇÃO 4: PERSISTÊNCIA
    // Salva/carrega o estado completo em um arquivo via serialização.
    // Grava primeiro em arquivo temporário para evitar corromper os dados.
    // =====================================================================

    static class Repositorio {
        private final Path arquivo;

        Repositorio(String caminho) { this.arquivo = Paths.get(caminho); }

        void salvar(Biblioteca b) throws IOException {
            Path tmp = Paths.get(arquivo + ".tmp");
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tmp))) {
                out.writeObject(b);
            }
            Files.move(tmp, arquivo, StandardCopyOption.REPLACE_EXISTING);
        }

        Biblioteca carregar() {
            if (!Files.exists(arquivo)) return new Biblioteca();
            try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(arquivo))) {
                return (Biblioteca) in.readObject();
            } catch (IOException | ClassNotFoundException ex) {
                System.out.println("Aviso: não foi possível ler os dados (" + ex.getMessage() + "). Iniciando vazio.");
                return new Biblioteca();
            }
        }
    }

    // =====================================================================
    // SEÇÃO 5: INTERFACE DE CONSOLE
    // Lê a entrada, delega ao serviço e trata as exceções de forma amigável.
    // =====================================================================

    private static final Scanner in = new Scanner(System.in);

    public static void main(String[] args) {
        Repositorio repo = new Repositorio("biblioteca.dat");
        Biblioteca bib = repo.carregar();

        boolean rodando = true;
        while (rodando) {
            exibirMenu();
            String opcao = in.nextLine().trim();
            try {
                switch (opcao) {
                    case "1" -> cadastrarLivro(bib);
                    case "2" -> cadastrarMembro(bib);
                    case "3" -> bib.listarLivros().forEach(System.out::println);
                    case "4" -> bib.listarMembros().forEach(System.out::println);
                    case "5" -> {
                        List<Livro> r = bib.buscar(ler("Termo de busca: "));
                        if (r.isEmpty()) System.out.println("Nenhum resultado.");
                        else r.forEach(System.out::println);
                    }
                    case "6" -> {
                        Emprestimo e = bib.emprestar(ler("ISBN: "), lerLong("ID do membro: "), LocalDate.now());
                        System.out.println("Emprestado! " + e);
                    }
                    case "7" -> {
                        BigDecimal multa = bib.devolver(lerLong("ID do empréstimo: "), LocalDate.now());
                        System.out.println("Devolvido. Multa: R$ " + multa);
                    }
                    case "8" -> System.out.println("Renovado! " + bib.renovar(lerLong("ID do empréstimo: "), LocalDate.now()));
                    case "9" -> bib.emprestimosAtivos().forEach(System.out::println);
                    case "10" -> {
                        List<Emprestimo> atrasados = bib.emprestimosAtrasados(LocalDate.now());
                        if (atrasados.isEmpty()) System.out.println("Nenhum atraso.");
                        else atrasados.forEach(e -> System.out.println(e + " | Multa atual: R$ "
                                + bib.calcularMulta(e, LocalDate.now())));
                    }
                    case "0" -> rodando = false;
                    default -> System.out.println("Opção inválida.");
                }
                // Salva após cada operação: evita perda de dados em caso de falha.
                repo.salvar(bib);
            } catch (BibliotecaException | IllegalArgumentException ex) {
                // Erros de regra ou de validação: mostra a mensagem sem derrubar o programa.
                System.out.println("Erro: " + ex.getMessage());
            } catch (IOException ex) {
                System.out.println("Falha ao salvar dados: " + ex.getMessage());
            }
        }
        System.out.println("Até logo!");
    }

    private static void exibirMenu() {
        System.out.println("""

                ===== BIBLIOTECA =====
                 1) Cadastrar livro       6) Emprestar
                 2) Cadastrar membro      7) Devolver
                 3) Listar livros         8) Renovar
                 4) Listar membros        9) Empréstimos ativos
                 5) Buscar livros        10) Empréstimos atrasados
                 0) Sair
                Opção:\s""");
    }

    private static void cadastrarLivro(Biblioteca bib) {
        Livro l = bib.cadastrarLivro(ler("ISBN: "), ler("Título: "), ler("Autor: "),
                ler("Categoria: "), (int) lerLong("Ano: "), (int) lerLong("Exemplares: "));
        System.out.println("Cadastrado: " + l);
    }

    private static void cadastrarMembro(Biblioteca bib) {
        String nome = ler("Nome: ");
        String email = ler("E-mail: ");
        TipoMembro tipo;
        try {
            tipo = TipoMembro.valueOf(ler("Tipo (ALUNO/PROFESSOR/VISITANTE): ").toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Tipo de membro inválido.");
        }
        System.out.println("Cadastrado: " + bib.cadastrarMembro(nome, email, tipo));
    }

    /** Lê uma linha de texto após exibir o rótulo. */
    private static String ler(String rotulo) {
        System.out.print(rotulo);
        return in.nextLine();
    }

    /** Lê um número inteiro, convertendo erros de formato em mensagem amigável. */
    private static long lerLong(String rotulo) {
        try {
            return Long.parseLong(ler(rotulo).trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Valor numérico inválido.");
        }
    }
}
